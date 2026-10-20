package com.example.server.editor;

import com.example.server.editor.EditorStore.TranscriptRow;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

/** The only transcript updates are conditional transitions of unfinished ASR documents. */
@Mapper
public interface TimedAsrMapper {
    @Select("SELECT "+EditorMapper.TRANSCRIPT_COLUMNS+" FROM media_transcripts WHERE media_id=#{mediaId} AND user_id=#{userId} AND source='ASR_TIMED' AND state='COMPLETED' ORDER BY revision DESC LIMIT 1")
    TranscriptRow completed(@Param("mediaId") Long mediaId,@Param("userId") Long userId);
    @Select("SELECT "+EditorMapper.TRANSCRIPT_COLUMNS+" FROM media_transcripts WHERE media_id=#{mediaId} AND user_id=#{userId} AND source='ASR_TIMED' ORDER BY revision DESC LIMIT 1")
    TranscriptRow latest(@Param("mediaId") Long mediaId,@Param("userId") Long userId);
    @Select("SELECT "+EditorMapper.TRANSCRIPT_COLUMNS+" FROM media_transcripts WHERE media_id=#{mediaId} AND user_id=#{userId} AND source='ASR_TIMED' AND state IN ('QUEUED','PROCESSING') ORDER BY revision DESC LIMIT 1")
    TranscriptRow active(@Param("mediaId") Long mediaId,@Param("userId") Long userId);
    @Update("UPDATE media_transcripts SET state='PROCESSING',updated_at=#{now} WHERE id=#{id} AND user_id=#{userId} AND source='ASR_TIMED' AND state='QUEUED'")
    int start(@Param("id") Long id,@Param("userId") Long userId,@Param("now") LocalDateTime now);
    @Update("UPDATE media_transcripts SET state='COMPLETED',duration_ms=#{duration},cues_json=#{cues},error_code=NULL,updated_at=#{now} WHERE id=#{id} AND user_id=#{userId} AND source='ASR_TIMED' AND state='PROCESSING'")
    int complete(@Param("id") Long id,@Param("userId") Long userId,@Param("duration") long duration,@Param("cues") String cues,@Param("now") LocalDateTime now);
    @Update("UPDATE media_transcripts SET state='FAILED',error_code=#{code},updated_at=#{now} WHERE id=#{id} AND user_id=#{userId} AND source='ASR_TIMED' AND state IN ('QUEUED','PROCESSING')")
    int fail(@Param("id") Long id,@Param("userId") Long userId,@Param("code") String code,@Param("now") LocalDateTime now);
    @Update("UPDATE media_transcripts SET state='FAILED',error_code='TIMED_ASR_INTERRUPTED',updated_at=#{now} WHERE source='ASR_TIMED' AND state IN ('QUEUED','PROCESSING')")
    int interrupt(@Param("now") LocalDateTime now);
}
