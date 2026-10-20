package com.example.server.editor;

import org.apache.ibatis.annotations.*;

@Mapper
public interface EditorMapper extends EditorStore, EditorStore.ExportStore {
    String TRANSCRIPT_COLUMNS="id, media_id AS mediaId, user_id AS userId, revision, source, duration_ms AS durationMs, state, error_code AS errorCode, cues_json AS cuesJson, created_at AS createdAt, updated_at AS updatedAt";
    String PROJECT_COLUMNS="id, media_id AS mediaId, user_id AS userId, transcript_id AS transcriptId, version, kept_cue_ids_json AS keptCueIdsJson, created_at AS createdAt, updated_at AS updatedAt";

    @Override @Select("SELECT id FROM media_files WHERE id=#{mediaId} AND user_id=#{userId} FOR UPDATE")
    Long lockOwnedMedia(@Param("mediaId") Long mediaId,@Param("userId") Long userId);
    @Override @Select("SELECT COALESCE(MAX(revision),0)+1 FROM media_transcripts WHERE media_id=#{mediaId}")
    int nextRevision(@Param("mediaId") Long mediaId);
    @Override @Insert("INSERT INTO media_transcripts (media_id,user_id,revision,source,duration_ms,state,error_code,cues_json,created_at,updated_at) VALUES (#{row.mediaId},#{row.userId},#{row.revision},#{row.source},#{row.durationMs},#{row.state},#{row.errorCode},#{row.cuesJson},#{row.createdAt},#{row.updatedAt})")
    @Options(useGeneratedKeys=true,keyProperty="row.id")
    int insertTranscript(@Param("row") TranscriptRow row);
    @Override @Select("SELECT "+TRANSCRIPT_COLUMNS+" FROM media_transcripts WHERE id=#{id} AND user_id=#{userId}")
    TranscriptRow findTranscript(@Param("id") Long id,@Param("userId") Long userId);
    @Override @Select("SELECT "+TRANSCRIPT_COLUMNS+" FROM media_transcripts WHERE media_id=#{mediaId} AND user_id=#{userId} ORDER BY revision DESC LIMIT 1")
    TranscriptRow latestTranscript(@Param("mediaId") Long mediaId,@Param("userId") Long userId);
    @Override @Insert("INSERT INTO media_edit_projects (media_id,user_id,transcript_id,version,kept_cue_ids_json,created_at,updated_at) VALUES (#{row.mediaId},#{row.userId},#{row.transcriptId},#{row.version},#{row.keptCueIdsJson},#{row.createdAt},#{row.updatedAt})")
    @Options(useGeneratedKeys=true,keyProperty="row.id")
    int insertProject(@Param("row") ProjectRow row);
    @Override @Select("SELECT "+PROJECT_COLUMNS+" FROM media_edit_projects WHERE id=#{id} AND user_id=#{userId}")
    ProjectRow findProject(@Param("id") Long id,@Param("userId") Long userId);
    @Override @Select("SELECT "+PROJECT_COLUMNS+" FROM media_edit_projects WHERE media_id=#{mediaId} AND user_id=#{userId} ORDER BY updated_at DESC,id DESC LIMIT 1")
    ProjectRow latestProject(@Param("mediaId") Long mediaId,@Param("userId") Long userId);
    @Override @Update("UPDATE media_edit_projects SET transcript_id=#{row.transcriptId},version=#{row.version},kept_cue_ids_json=#{row.keptCueIdsJson},updated_at=#{row.updatedAt} WHERE id=#{row.id} AND user_id=#{row.userId} AND media_id=#{row.mediaId} AND version=#{expectedVersion}")
    int updateProject(@Param("row") ProjectRow row,@Param("expectedVersion") int expectedVersion);

    String EXPORT_COLUMNS="id,user_id AS userId,project_id AS projectId,project_version AS projectVersion,media_id AS mediaId,transcript_id AS transcriptId,duration_ms AS durationMs,source_object AS sourceObject,source_hash AS sourceHash,request_hash AS requestHash,snapshot_json AS snapshotJson,preset,attempt,state,result_object AS resultObject,error_code AS errorCode,error_message AS errorMessage,created_at AS createdAt,updated_at AS updatedAt,started_at AS startedAt,completed_at AS completedAt";
    @Override @Select("SELECT id FROM media_files WHERE id=#{mediaId} AND user_id=#{userId} FOR UPDATE")
    Long lockExportMedia(@Param("mediaId") Long mediaId,@Param("userId") Long userId);
    @Override @Insert("INSERT INTO media_export_jobs (id,user_id,project_id,project_version,media_id,transcript_id,duration_ms,source_object,source_hash,request_hash,snapshot_json,preset,attempt,state,created_at,updated_at) VALUES (#{row.id},#{row.userId},#{row.projectId},#{row.projectVersion},#{row.mediaId},#{row.transcriptId},#{row.durationMs},#{row.sourceObject},#{row.sourceHash},#{row.requestHash},#{row.snapshotJson},#{row.preset},#{row.attempt},#{row.state},#{row.createdAt},#{row.updatedAt})")
    int insertExport(@Param("row") ExportRow row);
    @Override @Select("SELECT "+EXPORT_COLUMNS+" FROM media_export_jobs WHERE id=#{id} AND user_id=#{userId}")
    ExportRow findExport(@Param("id") String id,@Param("userId") Long userId);
    @Override @Select("SELECT "+EXPORT_COLUMNS+" FROM media_export_jobs WHERE user_id=#{userId} AND request_hash=#{requestHash}")
    ExportRow findExportByHash(@Param("userId") Long userId,@Param("requestHash") String requestHash);
    @Override @Select("SELECT "+EXPORT_COLUMNS+" FROM media_export_jobs WHERE project_id=#{projectId} AND user_id=#{userId} ORDER BY updated_at DESC,created_at DESC,id DESC LIMIT 1")
    ExportRow latestExport(@Param("projectId") Long projectId,@Param("userId") Long userId);
    @Override @Update("UPDATE media_export_jobs SET state='QUEUED',attempt=attempt+1,error_code=NULL,error_message=NULL,result_object=NULL,started_at=NULL,completed_at=NULL,updated_at=#{now} WHERE id=#{id} AND user_id=#{userId} AND state='FAILED' AND attempt=#{attempt} AND attempt<3")
    int retryExport(@Param("id") String id,@Param("userId") Long userId,@Param("attempt") int attempt,@Param("now") java.time.LocalDateTime now);
    @Override @Update("UPDATE media_export_jobs SET state='PROCESSING',started_at=#{now},updated_at=#{now} WHERE id=#{id} AND state='QUEUED' AND attempt=#{attempt}")
    int startExport(@Param("id") String id,@Param("attempt") int attempt,@Param("now") java.time.LocalDateTime now);
    @Override @Update("UPDATE media_export_jobs SET state='COMPLETED',result_object=#{resultObject},error_code=NULL,error_message=NULL,completed_at=#{now},updated_at=#{now} WHERE id=#{id} AND state='PROCESSING' AND attempt=#{attempt}")
    int completeExport(@Param("id") String id,@Param("attempt") int attempt,@Param("resultObject") String resultObject,@Param("now") java.time.LocalDateTime now);
    @Override @Update("UPDATE media_export_jobs SET state='FAILED',error_code=#{code},error_message=#{message},completed_at=#{now},updated_at=#{now} WHERE id=#{id} AND state IN ('QUEUED','PROCESSING') AND attempt=#{attempt}")
    int failExport(@Param("id") String id,@Param("attempt") int attempt,@Param("code") String code,@Param("message") String message,@Param("now") java.time.LocalDateTime now);
    @Override @Update("UPDATE media_export_jobs SET state='FAILED',result_object=#{resultObject},error_code=#{code},error_message=#{message},completed_at=#{now},updated_at=#{now} WHERE id=#{id} AND state IN ('QUEUED','PROCESSING') AND attempt=#{attempt}")
    int failExportWithResult(@Param("id") String id,@Param("attempt") int attempt,@Param("resultObject") String resultObject,@Param("code") String code,@Param("message") String message,@Param("now") java.time.LocalDateTime now);
    @Override @Update("UPDATE media_export_jobs SET result_object=#{resultObject},error_code='EXPORT_STORAGE_CLEANUP_FAILED',error_message='导出结果清理失败，请稍后重试',updated_at=#{now} WHERE id=#{id} AND state='FAILED' AND attempt=#{attempt}")
    int recordExportCleanupFailure(@Param("id") String id,@Param("attempt") int attempt,@Param("resultObject") String resultObject,@Param("now") java.time.LocalDateTime now);
    @Override @Update("UPDATE media_export_jobs SET state='FAILED',error_code='EXPORT_INTERRUPTED',error_message='导出服务重启，任务已中断，请重试',completed_at=#{now},updated_at=#{now} WHERE state IN ('QUEUED','PROCESSING')")
    int interruptExports(@Param("now") java.time.LocalDateTime now);
}
