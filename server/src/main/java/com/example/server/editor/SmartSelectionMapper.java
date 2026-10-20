package com.example.server.editor;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
@Mapper
public interface SmartSelectionMapper {
 String C="id,user_id AS userId,media_id AS mediaId,transcript_id AS transcriptId,transcript_revision AS transcriptRevision,goal,state,snapshot_json AS snapshotJson,result_json AS resultJson,model,prompt_version AS promptVersion,duration_ms AS durationMs,completed_batches AS completedBatches,total_batches AS totalBatches,error_code AS errorCode,error_message AS errorMessage,created_at AS createdAt,updated_at AS updatedAt";
 @Insert("INSERT INTO media_selection_jobs(id,user_id,media_id,transcript_id,transcript_revision,goal,state,snapshot_json,model,prompt_version,total_batches,created_at,updated_at) VALUES(#{r.id},#{r.userId},#{r.mediaId},#{r.transcriptId},#{r.transcriptRevision},#{r.goal},#{r.state},#{r.snapshotJson},#{r.model},#{r.promptVersion},#{r.totalBatches},#{r.createdAt},#{r.updatedAt})")
 int insert(@Param("r") SmartSelectionRow row);
 @Select("SELECT "+C+" FROM media_selection_jobs WHERE id=#{id} AND user_id=#{userId}")
 SmartSelectionRow find(@Param("id")String id,@Param("userId")Long userId);
 @Select("SELECT "+C+" FROM media_selection_jobs WHERE media_id=#{mediaId} AND transcript_id=#{transcriptId} AND user_id=#{userId} AND BINARY goal=BINARY #{goal} AND state IN ('QUEUED','PROCESSING') ORDER BY created_at DESC,id DESC LIMIT 1")
 SmartSelectionRow active(@Param("mediaId")Long mediaId,@Param("transcriptId")Long transcriptId,@Param("userId")Long userId,@Param("goal")String goal);
 @Select("SELECT "+C+" FROM media_selection_jobs WHERE media_id=#{mediaId} AND transcript_id=#{transcriptId} AND user_id=#{userId} ORDER BY created_at DESC,id DESC LIMIT 1")
 SmartSelectionRow latest(@Param("mediaId")Long mediaId,@Param("transcriptId")Long transcriptId,@Param("userId")Long userId);
 @Update("UPDATE media_selection_jobs SET state='PROCESSING',updated_at=#{now} WHERE id=#{id} AND state='QUEUED'")
 int start(@Param("id")String id,@Param("now")LocalDateTime now);
 @Update("UPDATE media_selection_jobs SET completed_batches=#{count},updated_at=#{now} WHERE id=#{id} AND state='PROCESSING'")
 int progress(@Param("id")String id,@Param("count")int count,@Param("now")LocalDateTime now);
 @Update("UPDATE media_selection_jobs SET state='COMPLETED',result_json=#{result},duration_ms=#{duration},error_code=NULL,error_message=NULL,updated_at=#{now} WHERE id=#{id} AND state='PROCESSING'")
 int complete(@Param("id")String id,@Param("result")String result,@Param("duration")long duration,@Param("now")LocalDateTime now);
 @Update("UPDATE media_selection_jobs SET state='FAILED',result_json=NULL,duration_ms=0,error_code=#{code},error_message=#{message},updated_at=#{now} WHERE id=#{id} AND state IN ('QUEUED','PROCESSING')")
 int fail(@Param("id")String id,@Param("code")String code,@Param("message")String message,@Param("now")LocalDateTime now);
 @Update("UPDATE media_selection_jobs SET state='FAILED',result_json=NULL,duration_ms=0,error_code='SELECTION_INTERRUPTED',error_message='服务重启，请重新生成推荐',updated_at=#{now} WHERE state IN ('QUEUED','PROCESSING')")
 int interrupt(@Param("now")LocalDateTime now);
}
