package com.example.server.editor;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.example.server.editor.EditorModels.*;
class ClipRangePlannerTest {
    private final ClipRangePlanner planner = new ClipRangePlanner();
    private TimedCue cue(String id, long start, long end) { return new TimedCue(id, start, end, "text"); }
    @Test void deletingMiddleCuePreservesTheTwoRemainingRanges() {
        assertEquals(List.of(new ClipRange(0, 2000), new ClipRange(4000, 6000)), planner.plan(List.of(cue("a", 0, 2000), cue("b", 2000, 4000), cue("c", 4000, 6000)), List.of("c", "a", "a"), 6000));
    }
    @Test void sortsAndMergesOverlapsAndAdjacency() {
        assertEquals(List.of(new ClipRange(0, 5000)), planner.plan(List.of(cue("c", 3000, 5000), cue("a", 0, 2000), cue("b", 1000, 3000)), List.of("c", "a", "b"), 6000));
    }
    @Test void emptySelectionIsValid() { assertEquals(List.of(), planner.plan(List.of(cue("a", 0, 1)), List.of(), 1)); }
    @Test void rejectsUnknownAndDuplicateIds() {
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(cue("a", 0, 1)), List.of("old"), 1));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(cue("a", 0, 1), cue("a", 1, 2)), List.of(), 2));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(cue(" ", 0, 1)), List.of(), 2));
    }
    @Test void rejectsInvalidUnselectedCueAndUnsafeDuration() {
        for (var times : new long[][] {{-1, 1}, {1, 1}, {2, 1}, {0, 3}, {0, Long.MAX_VALUE}})
            assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(cue("a", times[0], times[1])), List.of(), 2));
        for (long duration : new long[] {0, -1, 9007199254740992L})
            assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(), List.of(), duration));
    }
    @Test void rejectsMissingInputsAndBlankText() {
        assertThrows(IllegalArgumentException.class, () -> planner.plan(null, List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(), null, 1));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(new TimedCue("a", 0, 1, " ")), List.of(), 1));
    }
}
