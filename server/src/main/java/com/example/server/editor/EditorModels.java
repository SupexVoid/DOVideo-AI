package com.example.server.editor;

import java.time.LocalDateTime;
import java.util.List;

public final class EditorModels {
    private EditorModels() {}
    public record TimedCue(String id, long startMs, long endMs, String text) {}
    public record ClipRange(long startMs, long endMs) {}
    public record TranscriptDocument(Long id, Long mediaId, int revision, String source, long durationMs,
                                     String state, String errorCode, List<TimedCue> cues) {
        public TranscriptDocument { cues = List.copyOf(cues); }
    }
    public record EditProject(Long id, Long mediaId, Long transcriptId, int version,
                              List<String> keptCueIds, LocalDateTime updatedAt) {
        public EditProject { keptCueIds = List.copyOf(keptCueIds); }
    }
    public record ImportTranscriptRequest(Long mediaId, String format, String content) {}
    public record SaveProjectRequest(Long id, Long mediaId, Long transcriptId, Integer version, List<String> keptCueIds) {}
}
