package com.example.server.editor;

import com.example.server.common.ErrorCode;
import com.example.server.editor.EditorModels.ClipRange;
import com.example.server.editor.EditorModels.EditProject;
import com.example.server.editor.EditorStore.ExportRow;
import com.example.server.editor.EditorStore.ExportStore;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Service
public class ExportService implements SmartInitializingSingleton {
    private static final Logger log=LoggerFactory.getLogger(ExportService.class);
    private static final long MAX_SOURCE_BYTES=2L*1024*1024*1024;
    private final ExportStore store;
    private final EditorService editor;
    private final MediaService media;
    private final MinioUtils minio;
    private final FfmpegMediaExporter exporter;
    private final FfmpegMediaTools tools;
    private final ObjectMapper json;
    private final Executor executor;
    private final Path taskRoot;
    private final TransactionTemplate committedWrite;

    public record ExportRequest(Long projectId, Integer projectVersion, String preset) {}
    public record ExportJob(String id, Long projectId, int projectVersion, String state, int attempt,
                            String errorCode, String errorMessage, LocalDateTime createdAt, LocalDateTime updatedAt,
                            Long mediaId, Long transcriptId, List<ClipRange> ranges) {
        public ExportJob {ranges=List.copyOf(ranges);}
    }
    /** JSON snapshot and hash share an ordered immutable representation, without signed URLs. */
    public record ExportSnapshot(Long userId, Long mediaId, String sourceObject, String sourceHash,
                                  Long transcriptId, int transcriptRevision, long durationMs,
                                  String preset, List<ClipRange> ranges) {
        public ExportSnapshot { ranges=List.copyOf(ranges); }
    }
    private record Work(String id, int attempt, ExportSnapshot snapshot) {}
    private record Failure(String code,String message) {}
    private static final class SourceChanged extends RuntimeException {}

    public ExportService(ExportStore store, EditorService editor, MediaService media, MinioUtils minio,
                         FfmpegMediaExporter exporter, FfmpegMediaTools tools, ObjectMapper json,
                         @Qualifier("editorExportExecutor") Executor executor, PlatformTransactionManager transactions,
                         @Value("${editor.task-root:${java.io.tmpdir}/dovideo-editor}") String root) {
        this.store=store;this.editor=editor;this.media=media;this.minio=minio;this.exporter=exporter;
        this.tools=tools;this.json=json;this.executor=executor;this.taskRoot=Path.of(root).toAbsolutePath().normalize();
        committedWrite=new TransactionTemplate(transactions);
        committedWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public void afterSingletonsInstantiated() { interruptPending(); }
    public void interruptPending() { store.interruptExports(LocalDateTime.now()); }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ExportJob submit(ExportRequest request, Long userId) {
        requireUser(userId);
        if(request==null || request.projectId()==null || request.projectId()<=0 || request.projectVersion()==null
                || request.projectVersion()<0 || !"mp4".equals(request.preset()))
            throw new IllegalArgumentException("必须提供方案 ID、版本及 mp4 导出格式");
        var project=editor.requireProject(request.projectId(),userId);
        if(project.version()!=request.projectVersion()) throw new BusinessException(ErrorCode.CONFLICT,"编辑方案已更新，请保存并重新导出");
        ExportSnapshot snapshot=capture(project,userId);
        if(snapshot.ranges().isEmpty())throw new IllegalArgumentException("导出必须保留至少一个视频区间");
        if(store.lockExportMedia(project.mediaId(),userId)==null) throw new SecurityException("无权访问该视频");
        String snapshotJson=write(snapshot),requestHash=digest("SHA-256",snapshotJson.getBytes(StandardCharsets.UTF_8));
        ExportRow existing=store.findExportByHash(userId,requestHash);
        if(existing!=null) return reuse(existing,userId);
        LocalDateTime now=LocalDateTime.now();
        ExportRow row=new ExportRow();row.setId(UUID.randomUUID().toString());row.setUserId(userId);
        row.setProjectId(project.id());row.setProjectVersion(project.version());row.setMediaId(project.mediaId());
        row.setTranscriptId(snapshot.transcriptId());row.setDurationMs(snapshot.durationMs());
        row.setSourceObject(snapshot.sourceObject());row.setSourceHash(snapshot.sourceHash());row.setRequestHash(requestHash);row.setSnapshotJson(snapshotJson);
        row.setPreset(request.preset());row.setAttempt(1);row.setState("QUEUED");row.setCreatedAt(now);row.setUpdatedAt(now);
        try { store.insertExport(row); }
        catch(DuplicateKeyException concurrent) {
            ExportRow winner=store.findExportByHash(userId,requestHash);
            if(winner==null) throw concurrent;
            return reuse(winner,userId);
        }
        dispatchAfterCommit(row);
        return view(row);
    }

    private ExportJob reuse(ExportRow row,Long userId) {
        requireJobMedia(row,userId);
        if("FAILED".equals(row.getState())) {
            if(row.getAttempt()>=3) throw new BusinessException(ErrorCode.CONFLICT,"该导出已尝试 3 次，请检查原因后调整方案再导出");
            // Keep the old artifact identity until its idempotent deletion succeeds; never orphan it on retry.
            if(row.getResultObject()!=null)try { minio.removeFile(row.getResultObject()); }
            catch(RuntimeException cleanup) {throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"上次导出结果暂时无法清理，请稍后重试");}
            if(store.retryExport(row.getId(),userId,row.getAttempt(),LocalDateTime.now())==1) {
                row=store.findExport(row.getId(),userId);
                dispatchAfterCommit(row);
            } else row=store.findExport(row.getId(),userId);
        }
        return view(row);
    }

    public ExportJob get(String id,Long userId) { return view(requireJob(id,userId)); }
    public ExportJob latest(Long projectId,Long userId) {
        requireUser(userId);var project=editor.requireProject(projectId,userId);
        ExportRow row=store.latestExport(projectId,userId);
        if(row==null) {
            ExportSnapshot snapshot=capture(project,userId);
            if(snapshot.ranges().isEmpty())return null;
            row=store.findExportByHash(userId,digest("SHA-256",write(snapshot).getBytes(StandardCharsets.UTF_8)));
            if(row==null)return null;
        }
        requireJobMedia(row,userId);return view(row);
    }
    private ExportSnapshot capture(EditProject project,Long userId) {
        var transcript=editor.requireTranscript(project.transcriptId(),userId);
        if(!Objects.equals(project.mediaId(),transcript.mediaId()))throw new SecurityException("方案与视频不匹配");
        if(!"COMPLETED".equals(transcript.state()))throw new BusinessException(ErrorCode.CONFLICT,"字幕尚未完成");
        var ranges=new ClipRangePlanner().plan(transcript.cues(),project.keptCueIds(),transcript.durationMs());
        if(ranges.size()>1000)throw new IllegalArgumentException("导出最多支持 1000 个视频区间");
        MediaFile source=media.requireOwnedMedia(project.mediaId(),userId);
        return new ExportSnapshot(userId,project.mediaId(),stableSource(source.getFilePath()),normalizedHash(source.getContentHash()),
            transcript.id(),transcript.revision(),transcript.durationMs(),"mp4",ranges);
    }
    public String download(String id,Long userId) {
        ExportRow row=requireJob(id,userId);
        if(!"COMPLETED".equals(row.getState()) || row.getResultObject()==null)
            throw new BusinessException(ErrorCode.CONFLICT,"导出尚未完成，暂时无法下载");
        try { return minio.readableSource(row.getResultObject()); }
        catch(RuntimeException unavailable) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"下载地址暂时不可用，请稍后重试"); }
    }
    private ExportRow requireJob(String id,Long userId) {
        requireUser(userId);
        if(id==null || id.isBlank() || id.length()>36) throw new IllegalArgumentException("导出任务 ID 无效");
        ExportRow row=store.findExport(id,userId);
        if(row==null) throw new NoSuchElementException("导出任务不存在");
        requireJobMedia(row,userId);return row;
    }
    private void requireJobMedia(ExportRow row,Long userId) {
        if(!Objects.equals(row.getUserId(),userId))throw new NoSuchElementException("导出任务不存在");
        media.requireOwnedMedia(row.getMediaId(),userId);
    }

    private void dispatchAfterCommit(ExportRow row) {
        final Work work=new Work(row.getId(),row.getAttempt(),readSnapshot(row.getSnapshotJson()));
        Runnable dispatch=()->{
            try { executor.execute(()->run(work)); }
            catch(RejectedExecutionException rejected) {
                // afterCommit still has the original transaction's resources bound: use a NEW transaction.
                committedWrite.executeWithoutResult(status->store.failExport(work.id(),work.attempt(),
                    "EXPORT_QUEUE_FULL","导出队列已满，请稍后重试",LocalDateTime.now()));
            }
        };
        if(TransactionSynchronizationManager.isActualTransactionActive()) {
            if(!TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("导出事务同步不可用");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch.run(); }
            });
        } else dispatch.run(); // Direct construction is supported; production submit is transaction-proxied.
    }

    private void run(Work work) {
        Path directory=null;String result=null;Failure failure=null;
        try {
            if(store.startExport(work.id(),work.attempt(),LocalDateTime.now())!=1)return;
            verifySource(work.snapshot());
            Files.createDirectories(taskRoot);
            Path realRoot=taskRoot.toRealPath();
            directory=Files.createTempDirectory(realRoot,"export-").toRealPath();
            Path source=directory.resolve("source.media"),output=directory.resolve("result.mp4");
            MessageDigest md5=MessageDigest.getInstance("MD5");
            try(OutputStream file=Files.newOutputStream(source,StandardOpenOption.CREATE_NEW);
                OutputStream bounded=new FilterOutputStream(file) {
                    long written;
                    private void check(int count) throws IOException {if(count>MAX_SOURCE_BYTES-written)throw new IOException("Source size limit");written+=count;}
                    @Override public void write(int b) throws IOException {check(1);md5.update((byte)b);out.write(b);}
                    @Override public void write(byte[] b,int offset,int count) throws IOException {check(count);md5.update(b,offset,count);out.write(b,offset,count);}
                }) { minio.copyManagedFileTo(work.snapshot().sourceObject(),bounded); }
            String actualHash=HexFormat.of().formatHex(md5.digest());
            if(work.snapshot().sourceHash()!=null && !work.snapshot().sourceHash().equals(actualHash))throw new SourceChanged();
            verifySource(work.snapshot());
            exporter.export(source,work.snapshot().ranges(),output);
            long expected=work.snapshot().ranges().stream().mapToLong(r->r.endMs()-r.startMs()).sum();
            if(!Files.isRegularFile(output,LinkOption.NOFOLLOW_LINKS) || Files.size(output)==0
                    || Math.abs(tools.probeDurationMs(output)-expected)>200)throw new IllegalStateException("Output validation failed");
            verifySource(work.snapshot());
            result=minio.uploadLocalFile(output.toFile(),"export.mp4","editor-exports");
            if(result==null || result.isBlank())throw new IllegalStateException("Output upload failed");
        } catch(Exception error) { failure=safeFailure(error); }
        finally {
            if(directory!=null)try { cleanup(directory); }
            catch(Exception cleanup) { failure=new Failure("EXPORT_CLEANUP_FAILED","导出临时文件清理失败，请稍后重试"); }
        }
        if(failure!=null) {
            failWork(work,failure,result);
        } else {
            try {
                if(store.completeExport(work.id(),work.attempt(),result,LocalDateTime.now())!=1)minio.removeFile(result);
            } catch(RuntimeException persistence) {
                // Retain the object if an ambiguous commit already completed this attempt. Otherwise fail safely.
                failWork(work,new Failure("EXPORT_FAILED","导出结果暂时无法保存，请重试"),result);
                log.warn("editor_export_completion_write_failed job={} attempt={}",work.id(),work.attempt());
            }
        }
    }

    private void failWork(Work work,Failure failure,String result) {
        // Atomically retain any known object with FAILED, before another request can retry this attempt.
        int failed=result==null
            ?store.failExport(work.id(),work.attempt(),failure.code(),failure.message(),LocalDateTime.now())
            :store.failExportWithResult(work.id(),work.attempt(),result,failure.code(),failure.message(),LocalDateTime.now());
        if(failed!=1)return; // An already-completed ambiguous commit must retain its referenced object.
        if(result!=null)try {minio.removeFile(result);}
        catch(RuntimeException cleanup) {
            store.recordExportCleanupFailure(work.id(),work.attempt(),result,LocalDateTime.now());
            failure=new Failure("EXPORT_STORAGE_CLEANUP_FAILED","导出结果清理失败，请稍后重试");
        }
        log.warn("editor_export_failed job={} attempt={} code={}",work.id(),work.attempt(),failure.code());
    }

    private void verifySource(ExportSnapshot snapshot) {
        MediaFile current=media.requireOwnedMedia(snapshot.mediaId(),snapshot.userId());
        if(!Objects.equals(snapshot.sourceObject(),current.getFilePath())
                || !Objects.equals(snapshot.sourceHash(),normalizedHash(current.getContentHash())))throw new SourceChanged();
    }
    private void cleanup(Path directory) throws IOException {
        Path realRoot=taskRoot.toRealPath(),realDirectory=directory.toRealPath();
        if(!realDirectory.getParent().equals(realRoot))throw new IOException("Unsafe export cleanup");
        Files.deleteIfExists(realDirectory.resolve("source.media"));
        Files.deleteIfExists(realDirectory.resolve("result.mp4"));
        Files.delete(realDirectory);
    }
    private Failure safeFailure(Exception error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof SourceChanged)return new Failure("SOURCE_CHANGED","原视频已变化，请重新导入字幕并保存方案");
            if(cause instanceof InterruptedException) {Thread.currentThread().interrupt();return new Failure("EXPORT_INTERRUPTED","导出已中断，请重试");}
            if(cause instanceof TimeoutException || (cause instanceof IllegalStateException && cause.getMessage()!=null && cause.getMessage().contains("timed out")))
                return new Failure("EXPORT_TIMEOUT","导出处理超时，请缩短选段后重试");
        }
        return new Failure("EXPORT_FAILED","导出失败，请检查视频、存储及 FFmpeg 配置后重试");
    }
    private String stableSource(String value) {
        if(value==null || value.isBlank())throw new IllegalArgumentException("源视频不可用");
        URI uri=URI.create(value);
        if(uri.getRawQuery()!=null || uri.getRawFragment()!=null)throw new IllegalArgumentException("源视频必须使用稳定存储标识");
        return value;
    }
    private String normalizedHash(String hash) {
        if(hash==null || hash.isBlank())return null;
        if(!hash.matches("(?i)[0-9a-f]{32}"))throw new IllegalArgumentException("视频内容标识无效");
        return hash.toLowerCase(Locale.ROOT);
    }
    private void requireUser(Long userId) {if(userId==null || userId<=0)throw new BusinessException(ErrorCode.UNAUTHORIZED,"请先登录");}
    private ExportJob view(ExportRow r) {
        ExportSnapshot snapshot=readSnapshot(r.getSnapshotJson());
        return new ExportJob(r.getId(),r.getProjectId(),r.getProjectVersion(),r.getState(),r.getAttempt(),r.getErrorCode(),r.getErrorMessage(),
            r.getCreatedAt(),r.getUpdatedAt(),snapshot.mediaId(),snapshot.transcriptId(),snapshot.ranges());
    }
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("无法保存导出快照",e);}}
    private ExportSnapshot readSnapshot(String value) {try{return json.readValue(value,ExportSnapshot.class);}catch(JsonProcessingException e){throw new IllegalStateException("无法读取导出快照",e);}}
    private String digest(String algorithm,byte[] value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
