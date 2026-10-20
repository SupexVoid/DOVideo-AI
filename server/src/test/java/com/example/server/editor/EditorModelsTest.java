package com.example.server.editor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.example.server.editor.EditorModels.*;
class EditorModelsTest {
    @Test void recordsHaveValueSemanticsAndMillisecondAccessors() {
        assertEquals(new ClipRange(1, 2), new ClipRange(1, 2));
        var cue = new TimedCue("a", 1, 2, "hello");
        assertEquals(1, cue.startMs());
        assertEquals(2, cue.endMs());
        assertEquals("a", cue.id());
        assertEquals("hello", cue.text());
    }
}
