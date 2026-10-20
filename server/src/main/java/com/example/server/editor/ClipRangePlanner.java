package com.example.server.editor;
import java.util.*;
import com.example.server.editor.EditorModels.TimedCue;
import com.example.server.editor.EditorModels.ClipRange;
public class ClipRangePlanner {
    static final long MAX_SAFE_MS = 9007199254740991L;

    static void validateDuration(long durationMs) {
        if (durationMs <= 0 || durationMs > MAX_SAFE_MS) throw new IllegalArgumentException("Invalid duration: positive safe integer milliseconds required");
    }

    public List<ClipRange> plan(List<TimedCue> cues, List<String> keptCueIds, long durationMs) {
        validateDuration(durationMs);
        if (cues == null || keptCueIds == null) throw new IllegalArgumentException("Cues and selected IDs are required");
        Map<String, TimedCue> byId = new HashMap<>();
        for (TimedCue cue : cues) {
            if (cue == null || cue.id() == null || cue.id().isBlank()) throw new IllegalArgumentException("Invalid cue ID");
            if (byId.putIfAbsent(cue.id(), cue) != null) throw new IllegalArgumentException("Duplicate cue ID: " + cue.id());
            if (cue.startMs() < 0 || cue.startMs() >= cue.endMs() || cue.endMs() > durationMs) throw new IllegalArgumentException("Invalid time range for cue " + cue.id());
            if (cue.text() == null || cue.text().isBlank()) throw new IllegalArgumentException("Empty text for cue " + cue.id());
        }
        List<TimedCue> selected = new ArrayList<>();
        for (String id : new HashSet<>(keptCueIds)) {
            if (!byId.containsKey(id)) throw new IllegalArgumentException("Unknown cue ID: " + id);
            selected.add(byId.get(id));
        }
        selected.sort(Comparator.comparingLong(TimedCue::startMs).thenComparingLong(TimedCue::endMs));
        List<ClipRange> ranges = new ArrayList<>();
        for (TimedCue cue : selected) {
            if (!ranges.isEmpty() && cue.startMs() <= ranges.getLast().endMs()) {
                ClipRange last = ranges.removeLast();
                ranges.add(new ClipRange(last.startMs(), Math.max(last.endMs(), cue.endMs())));
            } else ranges.add(new ClipRange(cue.startMs(), cue.endMs()));
        }
        return List.copyOf(ranges);
    }
}
