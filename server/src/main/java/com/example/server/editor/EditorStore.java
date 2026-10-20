package com.example.server.editor;

import lombok.Data;
import java.time.LocalDateTime;

/** Persistence boundary; completed transcripts have no update operation. */
public interface EditorStore {
    Long lockOwnedMedia(Long mediaId, Long userId);
    int nextRevision(Long mediaId);
    int insertTranscript(TranscriptRow row);
    TranscriptRow findTranscript(Long id, Long userId);
    TranscriptRow latestTranscript(Long mediaId, Long userId);
    int insertProject(ProjectRow row);
    ProjectRow findProject(Long id, Long userId);
    ProjectRow latestProject(Long mediaId, Long userId);
    int updateProject(ProjectRow row, int expectedVersion);

    /** Separate persistence facet so transcript/project consumers need no export operations. */
    interface ExportStore {
        Long lockExportMedia(Long mediaId, Long userId);
        int insertExport(ExportRow row);
        ExportRow findExport(String id, Long userId);
        ExportRow findExportByHash(Long userId, String requestHash);
        ExportRow latestExport(Long projectId, Long userId);
        int retryExport(String id, Long userId, int attempt, LocalDateTime now);
        int startExport(String id, int attempt, LocalDateTime now);
        int completeExport(String id, int attempt, String resultObject, LocalDateTime now);
        int failExport(String id, int attempt, String code, String message, LocalDateTime now);
        int failExportWithResult(String id, int attempt, String resultObject, String code, String message, LocalDateTime now);
        int recordExportCleanupFailure(String id, int attempt, String resultObject, LocalDateTime now);
        int interruptExports(LocalDateTime now);
    }

    @Data class ExportRow {
        private String id, sourceObject, sourceHash, requestHash, snapshotJson, preset, state;
        private String resultObject, errorCode, errorMessage;
        private Long userId, projectId, mediaId, transcriptId;
        private int projectVersion, attempt;
        private long durationMs;
        private LocalDateTime createdAt, updatedAt, startedAt, completedAt;
    }

    @Data class TranscriptRow {
        private Long id, mediaId, userId;
        private int revision;
        private long durationMs;
        private String source, state, errorCode, cuesJson;
        private LocalDateTime createdAt, updatedAt;
    }
    @Data class ProjectRow {
        private Long id, mediaId, userId, transcriptId;
        private int version;
        private String keptCueIdsJson;
        private LocalDateTime createdAt, updatedAt;
    }
}
