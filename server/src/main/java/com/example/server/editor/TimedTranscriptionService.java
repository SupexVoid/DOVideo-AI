package com.example.server.editor;

import com.example.server.common.ErrorCode;
import com.example.server.editor.EditorModels.*;
import com.example.server.editor.EditorStore.TranscriptRow;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.exception.BusinessException;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class TimedTranscriptionService implements SmartInitializingSingleton {
    private static final long CHUNK_MS=60000,MAX_SOURCE_BYTES=2L*1024*1024*1024;
    private final EditorStore store;
    private final TimedAsrMapper jobs;
    private final MediaService media;
    private final MediaFileMapper mediaFiles;
    private final MinioUtils minio;
    private final FfmpegMediaTools tools;
    private final TimedAsrProvider provider;
    private final ObjectMapper json;
    private final Executor executor;
    private final Path taskRoot;
    private final TransactionTemplate committedWrite;
    private record Work(Long id,Long mediaId,Long userId,String source,String hash) {}
    private static final class SourceChanged extends RuntimeException {}

    public TimedTranscriptionService(EditorStore store,TimedAsrMapper jobs,MediaService media,MediaFileMapper mediaFiles,MinioUtils minio,
            FfmpegMediaTools tools,TimedAsrProvider provider,ObjectMapper json,
            @Qualifier("editorTimedAsrExecutor") Executor executor,PlatformTransactionManager transactions,
            @Value("${editor.task-root:${java.io.tmpdir}/dovideo-editor}") String root) {
        this.store=store;this.jobs=jobs;this.media=media;this.mediaFiles=mediaFiles;this.minio=minio;this.tools=tools;this.provider=provider;
        this.json=json;this.executor=executor;this.taskRoot=Path.of(root).toAbsolutePath().normalize();
        committedWrite=new TransactionTemplate(transactions);committedWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public void afterSingletonsInstantiated(){interruptPending();}
    public void interruptPending(){jobs.interrupt(LocalDateTime.now());}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TranscriptDocument submit(Long mediaId,Long userId) {
        MediaFile source=requireMedia(mediaId,userId);
        String stable=stableSource(source.getFilePath()),hash=normalizedHash(source.getContentHash());
        if(store.lockOwnedMedia(mediaId,userId)==null)throw new SecurityException("无权访问该文件");
        TranscriptRow completed=jobs.completed(mediaId,userId);
        if(completed!=null)return document(completed);
        TranscriptRow active=jobs.active(mediaId,userId);
        if(active!=null)return document(active);
        try{provider.requireConfigured();}catch(IllegalStateException unavailable){throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"时间轴转写未配置，可导入字幕");}
        TranscriptRow row=new TranscriptRow();row.setMediaId(mediaId);row.setUserId(userId);
        row.setRevision(store.nextRevision(mediaId));row.setSource("ASR_TIMED");row.setState("QUEUED");
        row.setDurationMs(0);row.setCuesJson("[]");row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(row.getCreatedAt());
        store.insertTranscript(row);
        dispatchAfterCommit(new Work(row.getId(),mediaId,userId,stable,hash));
        return document(row);
    }
    public TranscriptDocument completed(Long mediaId,Long userId) {
        requireMedia(mediaId,userId);TranscriptRow row=jobs.completed(mediaId,userId);
        if(row==null)return null;
        if(!Objects.equals(row.getMediaId(),mediaId)||!Objects.equals(row.getUserId(),userId)||!"ASR_TIMED".equals(row.getSource())||!"COMPLETED".equals(row.getState()))throw new SecurityException("无权访问该转写");
        return document(row);
    }
    public TranscriptDocument status(Long mediaId,Long userId) {
        requireMedia(mediaId,userId);TranscriptRow row=jobs.latest(mediaId,userId);
        if(row==null)return null;
        if(!Objects.equals(row.getMediaId(),mediaId)||!Objects.equals(row.getUserId(),userId)||!"ASR_TIMED".equals(row.getSource()))throw new SecurityException("无权访问该转写");
        return document(row);
    }
    private void dispatchAfterCommit(Work work) {
        Runnable dispatch=()->{
            try{executor.execute(()->run(work));}
            catch(RejectedExecutionException full){
                // The request transaction's resources may still be bound inside afterCommit.
                committedWrite.executeWithoutResult(tx->jobs.fail(work.id(),work.userId(),"TIMED_ASR_QUEUE_FULL",LocalDateTime.now()));
            }
        };
        if(TransactionSynchronizationManager.isActualTransactionActive()) {
            if(!TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("转写事务同步不可用");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){dispatch.run();}});
        } else dispatch.run();
    }
    private void run(Work work) {
        Path directory=null;String failure=null,cuesJson=null;long duration=0;
        long deadline=System.nanoTime()+Duration.ofHours(2).toNanos();
        try {
            // Start may fail before or after MySQL commits the transition. Keep it
            // inside the failure boundary so either unfinished state can be failed.
            if(jobs.start(work.id(),work.userId(),LocalDateTime.now())!=1)return;
            verifySource(work);Files.createDirectories(taskRoot);Path realRoot=taskRoot.toRealPath();
            directory=Files.createTempDirectory(realRoot,"timed-asr-").toRealPath();
            Path source=directory.resolve("source.media"),chunk=directory.resolve("chunk.wav");
            MessageDigest digest=MessageDigest.getInstance("MD5");
            try(OutputStream file=Files.newOutputStream(source,StandardOpenOption.CREATE_NEW);
                OutputStream bounded=new FilterOutputStream(file){
                    long count;
                    private void check(int size)throws IOException{if(size>MAX_SOURCE_BYTES-count)throw new IOException("Source size limit");count+=size;}
                    @Override public void write(int b)throws IOException{check(1);digest.update((byte)b);out.write(b);}
                    @Override public void write(byte[] b,int offset,int size)throws IOException{check(size);digest.update(b,offset,size);out.write(b,offset,size);}
                }){minio.copyManagedFileTo(work.source(),bounded);}
            if(work.hash()!=null&&!work.hash().equals(HexFormat.of().formatHex(digest.digest())))throw new SourceChanged();
            verifySource(work);duration=tools.probeDurationMs(source);
            if(duration<=0||duration>EditorService.MAX_DURATION_MS)throw new IllegalArgumentException("视频时长无效");
            List<TimedCue> cues=new ArrayList<>();long textBytes=0;
            for(long offset=0;offset<duration;offset+=CHUNK_MS) {
                checkDeadline(deadline);long length=Math.min(CHUNK_MS,duration-offset);
                tools.extractAudioChunk(source,chunk,offset,length);
                try {
                    long actual=tools.probeAudioDurationMs(chunk);
                    if(actual<=0 || Math.abs(actual-length)>2)throw new IllegalStateException("Audio chunk duration mismatch");
                    List<TimedCue> partial=provider.transcribe(chunk);
                    if(partial==null)throw new IllegalStateException("Missing provider cues");
                    long previous=-1;
                    for(TimedCue cue:partial) {
                        if(cue==null||cue.startMs()<0||cue.endMs()<=cue.startMs()||cue.endMs()>actual||cue.endMs()>length||cue.startMs()<previous
                                ||cue.text()==null||cue.text().isBlank())throw new IllegalStateException("Invalid chunk timestamps");
                        int bytes=cue.text().getBytes(StandardCharsets.UTF_8).length;
                        if(bytes>32768||(textBytes+=bytes)>1024*1024||cues.size()>=10000)throw new IllegalStateException("Transcript size limit");
                        cues.add(new TimedCue("asr-"+work.id()+"-"+(cues.size()+1),offset+cue.startMs(),offset+cue.endMs(),cue.text()));previous=cue.startMs();
                    }
                } finally{Files.deleteIfExists(chunk);}
            }
            checkDeadline(deadline);verifySource(work);
            if(cues.isEmpty())throw new IllegalStateException("No timed speech segments");
            // Reuse the editor's range validator, including complete video bounds.
            new ClipRangePlanner().plan(cues,List.of(),duration);cuesJson=write(cues);
        } catch(Exception error){failure=failureCode(error);}
        finally {
            if(directory!=null)try{cleanup(directory);}catch(Exception cleanup){failure="TIMED_ASR_CLEANUP_FAILED";}
        }
        if(failure!=null)jobs.fail(work.id(),work.userId(),failure,LocalDateTime.now());
        else {
            try{
                if(jobs.complete(work.id(),work.userId(),duration,cuesJson,LocalDateTime.now())==1) {
                    TranscriptDocument completed=completed(work.mediaId(),work.userId());
                    if(completed!=null)syncText(completed,work.userId());
                }
            }
            catch(RuntimeException persistence){
                // A connection can fail after the commit reached MySQL. Conditional failure
                // never overwrites a document that actually completed in that ambiguous case.
                jobs.fail(work.id(),work.userId(),"TIMED_ASR_SAVE_FAILED",LocalDateTime.now());
                org.slf4j.LoggerFactory.getLogger(TimedTranscriptionService.class)
                    .warn("editor_timed_asr_completion_write_failed transcript={}",work.id());
            }
        }
    }
    /** Only the text field is synchronized; immutable editor documents and concurrent summaries are untouched. */
    public void syncText(TranscriptDocument document,Long userId) {
        requireMedia(document.mediaId(),userId);
        if(!"ASR_TIMED".equals(document.source())||!"COMPLETED".equals(document.state()))throw new IllegalArgumentException("自动转写尚未完成");
        new ClipRangePlanner().plan(document.cues(),List.of(),document.durationMs());
        if(document.cues().isEmpty())throw new IllegalArgumentException("自动转写为空");
        MediaFile update=new MediaFile();update.setTranscriptText(document.cues().stream().map(TimedCue::text).collect(java.util.stream.Collectors.joining("\n")));
        if(mediaFiles.update(update,new UpdateWrapper<MediaFile>().eq("id",document.mediaId()).eq("user_id",userId))>0)media.invalidateUserList(userId);
    }
    private void verifySource(Work work) {
        MediaFile current=requireMedia(work.mediaId(),work.userId());
        if(!Objects.equals(current.getFilePath(),work.source())||!Objects.equals(normalizedHash(current.getContentHash()),work.hash()))throw new SourceChanged();
    }
    private void cleanup(Path directory)throws IOException {
        Path root=taskRoot.toRealPath(),job=directory.toRealPath();
        if(!job.getParent().equals(root))throw new IOException("Unsafe ASR cleanup");
        Files.deleteIfExists(job.resolve("chunk.wav"));Files.deleteIfExists(job.resolve("source.media"));Files.delete(job);
    }
    private void checkDeadline(long deadline)throws InterruptedException,TimeoutException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(System.nanoTime()>deadline)throw new TimeoutException();
    }
    private String failureCode(Exception error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof SourceChanged)return "TIMED_ASR_SOURCE_CHANGED";
            if(cause instanceof InterruptedException){Thread.currentThread().interrupt();return "TIMED_ASR_INTERRUPTED";}
            if(cause instanceof TimeoutException || (cause.getMessage()!=null && cause.getMessage().contains("timed out")))return "TIMED_ASR_TIMEOUT";
        }
        return "TIMED_ASR_FAILED";
    }
    private MediaFile requireMedia(Long mediaId,Long userId) {
        if(userId==null||userId<=0)throw new BusinessException(ErrorCode.UNAUTHORIZED,"请先登录");
        if(mediaId==null||mediaId<=0)throw new IllegalArgumentException("视频 ID 无效");
        return media.requireOwnedMedia(mediaId,userId);
    }
    private String stableSource(String source) {
        if(source==null||source.isBlank())throw new IllegalArgumentException("原视频不可用");
        URI uri=URI.create(source);
        if(uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException("原视频必须使用稳定存储标识");
        return source;
    }
    private String normalizedHash(String hash) {
        if(hash==null||hash.isBlank())return null;
        if(!hash.matches("(?i)[0-9a-f]{32}"))throw new IllegalArgumentException("视频内容标识无效");
        return hash.toLowerCase(Locale.ROOT);
    }
    private TranscriptDocument document(TranscriptRow row) {
        try{return new TranscriptDocument(row.getId(),row.getMediaId(),row.getRevision(),row.getSource(),row.getDurationMs(),row.getState(),row.getErrorCode(),json.readValue(row.getCuesJson(),new TypeReference<List<TimedCue>>(){}));}
        catch(JsonProcessingException invalid){throw new IllegalStateException("转写数据无法读取",invalid);}
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException error){throw new IllegalStateException("转写数据无法保存",error);}}
}
