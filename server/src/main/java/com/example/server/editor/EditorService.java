package com.example.server.editor;

import com.example.server.common.ErrorCode;
import com.example.server.editor.EditorModels.*;
import com.example.server.editor.EditorStore.*;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class EditorService {
    public static final long MAX_DURATION_MS = 12L * 60 * 60 * 1000;
    private static final long MAX_SOURCE_BYTES = 2L * 1024 * 1024 * 1024;
    private final EditorStore store;
    private final MediaService media;
    private final MinioUtils minio;
    private final FfmpegMediaTools tools;
    private final ObjectMapper json;
    private final Path taskRoot;
    private final ClipRangePlanner planner = new ClipRangePlanner();

    public EditorService(EditorStore store, MediaService media, MinioUtils minio, FfmpegMediaTools tools,
                         ObjectMapper json, @Value("${editor.task-root:${java.io.tmpdir}/dovideo-editor}") String taskRoot) {
        this.store=store; this.media=media; this.minio=minio; this.tools=tools; this.json=json;
        this.taskRoot=Path.of(taskRoot).toAbsolutePath().normalize();
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TranscriptDocument importTranscript(ImportTranscriptRequest request, Long userId) {
        if(request==null) throw new IllegalArgumentException("字幕导入参数不能为空");
        MediaFile source=requireMedia(request.mediaId(),userId);
        // Reject excessive/invalid request text before copying a potentially large source.
        if(request.content()==null || request.content().length()>1024*1024
                || request.content().getBytes(StandardCharsets.UTF_8).length>1024*1024)
            throw new IllegalArgumentException("字幕内容不能为空且不能超过 1 MiB");
        if(!"srt".equals(request.format()) && !"vtt".equals(request.format()))
            throw new IllegalArgumentException("字幕格式仅支持 srt 或 vtt");
        long duration=probeManagedSource(source);
        List<TimedCue> cues=new SubtitleParser().parse(request.format(),request.content(),duration);
        validateTimeline(cues,duration);
        if(store.lockOwnedMedia(request.mediaId(),userId)==null) throw new SecurityException("无权访问该文件");
        TranscriptRow row=new TranscriptRow();
        row.setMediaId(request.mediaId()); row.setUserId(userId);
        // The owned media row lock serializes revision allocation, including concurrent imports.
        row.setRevision(store.nextRevision(request.mediaId()));
        row.setSource("SUBTITLE_IMPORT"); row.setState("COMPLETED"); row.setDurationMs(duration);
        row.setCuesJson(write(cues)); row.setCreatedAt(LocalDateTime.now()); row.setUpdatedAt(row.getCreatedAt());
        store.insertTranscript(row);
        return document(row);
    }

    public TranscriptDocument getTranscript(Long mediaId, Long transcriptId, Long userId) {
        requireMedia(mediaId,userId);
        if(transcriptId==null) {
            TranscriptRow row=store.latestTranscript(mediaId,userId);
            return row==null?null:document(requireTranscriptRow(row.getId(),userId,mediaId));
        }
        return document(requireTranscriptRow(transcriptId,userId,mediaId));
    }

    /** Also used by export snapshot creation; verifies both document and original media ownership. */
    public TranscriptDocument requireTranscript(Long transcriptId, Long userId) {
        return document(requireTranscriptRow(transcriptId,userId,null));
    }

    @Transactional
    public EditProject saveProject(SaveProjectRequest request, Long userId) {
        if(request==null) throw new IllegalArgumentException("编辑方案不能为空");
        requireMedia(request.mediaId(),userId);
        if(request.version()==null || request.version()<0 || request.keptCueIds()==null)
            throw new IllegalArgumentException("必须提供有效的 version 和 keptCueIds");
        if(request.keptCueIds().size()>10000) throw new IllegalArgumentException("选段数量不能超过 10000");
        TranscriptRow transcript=requireTranscriptRow(request.transcriptId(),userId,request.mediaId());
        if(!"COMPLETED".equals(transcript.getState())) throw new BusinessException(ErrorCode.CONFLICT,"字幕尚未完成，无法保存编辑方案");
        List<TimedCue> cues=readCues(transcript.getCuesJson());
        validateTimeline(cues,transcript.getDurationMs());
        planner.plan(cues,request.keptCueIds(),transcript.getDurationMs());
        ProjectRow previous=null;
        if(request.id()!=null) {
            previous=requireProjectRow(request.id(),userId);
            if(!Objects.equals(previous.getMediaId(),request.mediaId())) throw new SecurityException("方案与视频不匹配");
        } else if(request.version()!=0) throw new BusinessException(ErrorCode.CONFLICT,"新方案 version 必须为 0");
        ProjectRow row=new ProjectRow();
        row.setId(request.id()); row.setMediaId(request.mediaId()); row.setUserId(userId); row.setTranscriptId(request.transcriptId());
        row.setVersion(request.version()+1);
        row.setKeptCueIdsJson(write(new ArrayList<>(new LinkedHashSet<>(request.keptCueIds()))));
        row.setUpdatedAt(LocalDateTime.now()); row.setCreatedAt(previous==null?row.getUpdatedAt():previous.getCreatedAt());
        if(previous==null) store.insertProject(row);
        else if(store.updateProject(row,request.version())!=1) throw new BusinessException(ErrorCode.CONFLICT,"方案已被更新，请重新加载后再保存");
        return project(row);
    }

    public EditProject getProject(Long mediaId,Long userId) {
        requireMedia(mediaId,userId);
        ProjectRow row=store.latestProject(mediaId,userId);
        return row==null?null:project(requireProjectRow(row.getId(),userId));
    }

    public EditProject requireProject(Long id,Long userId) { return project(requireProjectRow(id,userId)); }

    public byte[] frame(Long mediaId,long atMs,Long userId) {
        MediaFile source=requireMedia(mediaId,userId);
        if(atMs<0 || atMs>=MAX_DURATION_MS) throw new IllegalArgumentException("截图时间无效");
        return withManagedSource(source,path->{
            long duration=tools.probeDurationMs(path);
            if(duration>MAX_DURATION_MS || atMs>=duration) throw new IllegalArgumentException("截图时间必须位于视频内");
            return tools.extractFrame(path,atMs);
        });
    }

    private ProjectRow requireProjectRow(Long id,Long userId) {
        positiveId(id,"方案");
        ProjectRow row=store.findProject(id,userId);
        if(row==null) throw new NoSuchElementException("编辑方案不存在");
        requireMedia(row.getMediaId(),userId);
        requireTranscriptRow(row.getTranscriptId(),userId,row.getMediaId());
        return row;
    }

    private TranscriptRow requireTranscriptRow(Long id,Long userId,Long expectedMediaId) {
        positiveId(id,"字幕文档");
        TranscriptRow row=store.findTranscript(id,userId);
        if(row==null) throw new NoSuchElementException("字幕文档不存在");
        requireMedia(row.getMediaId(),userId);
        if(expectedMediaId!=null && !Objects.equals(expectedMediaId,row.getMediaId())) throw new SecurityException("字幕与视频不匹配");
        return row;
    }

    private MediaFile requireMedia(Long id,Long userId) {
        positiveId(id,"视频");
        if(userId==null || userId<=0) throw new BusinessException(ErrorCode.UNAUTHORIZED,"请先登录");
        return media.requireOwnedMedia(id,userId);
    }
    private void positiveId(Long id,String label) { if(id==null || id<=0) throw new IllegalArgumentException(label+" ID 无效"); }

    private void validateTimeline(List<TimedCue> cues,long duration) {
        if(duration<=0 || duration>MAX_DURATION_MS) throw new IllegalArgumentException("视频时长必须大于 0 且不超过 12 小时");
        if(cues==null || cues.isEmpty() || cues.size()>10000) throw new IllegalArgumentException("字幕数量必须为 1 到 10000");
        long bytes=0;
        for(TimedCue cue:cues) {
            if(cue==null || cue.id()==null || cue.id().length()>128 || cue.text()==null) throw new IllegalArgumentException("字幕内容或 ID 无效");
            int length=cue.text().getBytes(StandardCharsets.UTF_8).length;
            if(length>32768) throw new IllegalArgumentException("单条字幕文本不能超过 32 KiB");
            bytes+=length;
            if(bytes>1024*1024) throw new IllegalArgumentException("字幕文本总量不能超过 1 MiB");
        }
        planner.plan(cues,List.of(),duration);
    }

    private long probeManagedSource(MediaFile mediaFile) {
        return withManagedSource(mediaFile,path->{
            long duration=tools.probeDurationMs(path);
            if(duration<=0 || duration>MAX_DURATION_MS) throw new IllegalArgumentException("视频时长必须大于 0 且不超过 12 小时");
            return duration;
        });
    }

    private <T> T withManagedSource(MediaFile mediaFile,java.util.function.Function<Path,T> operation) {
        Path directory=null,source=null;
        try {
            Files.createDirectories(taskRoot);
            directory=Files.createTempDirectory(taskRoot,"import-").toAbsolutePath().normalize();
            source=directory.resolve("source.media");
            try(OutputStream output=Files.newOutputStream(source,StandardOpenOption.CREATE_NEW);
                OutputStream bounded=new FilterOutputStream(output) {
                    long written;
                    @Override public void write(int value) throws IOException { check(1);out.write(value); }
                    @Override public void write(byte[] bytes,int offset,int length) throws IOException { check(length);out.write(bytes,offset,length); }
                    private void check(int count) { written+=count; if(written>MAX_SOURCE_BYTES) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE,"源视频不能超过 2 GiB"); }
                }) {
                minio.copyManagedFileTo(mediaFile.getFilePath(),bounded);
            }
            return operation.apply(source);
        } catch(IOException | IllegalStateException error) {
            if(error.getCause() instanceof BusinessException business) throw business;
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"视频读取或探测暂不可用，请检查存储及 FFmpeg 配置");
        } finally {
            // Only these exact caller-created paths can be deleted; never recurse through the task root.
            if(directory!=null && directory.getParent().equals(taskRoot)) {
                try { if(source!=null && source.getParent().equals(directory)) Files.deleteIfExists(source); Files.deleteIfExists(directory); }
                catch(IOException cleanup) { org.slf4j.LoggerFactory.getLogger(EditorService.class).warn("editor_source_cleanup_failed",cleanup); }
            }
        }
    }
    private TranscriptDocument document(TranscriptRow row) { return new TranscriptDocument(row.getId(),row.getMediaId(),row.getRevision(),row.getSource(),row.getDurationMs(),row.getState(),row.getErrorCode(),readCues(row.getCuesJson())); }
    private EditProject project(ProjectRow row) { return new EditProject(row.getId(),row.getMediaId(),row.getTranscriptId(),row.getVersion(),readIds(row.getKeptCueIdsJson()),row.getUpdatedAt()); }
    private String write(Object value) { try {return json.writeValueAsString(value);} catch(JsonProcessingException e) {throw new IllegalStateException("无法保存编辑数据",e);} }
    private List<TimedCue> readCues(String value) { try {return json.readValue(value,new TypeReference<List<TimedCue>>(){});} catch(JsonProcessingException e) {throw new IllegalStateException("字幕数据无法读取",e);} }
    private List<String> readIds(String value) { try {return json.readValue(value,new TypeReference<List<String>>(){});} catch(JsonProcessingException e) {throw new IllegalStateException("选段数据无法读取",e);} }
}
