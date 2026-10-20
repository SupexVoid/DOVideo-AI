package com.example.server.editor;

import java.nio.file.Path;
import java.util.List;
import com.example.server.editor.EditorModels.TimedCue;

/** Segment timestamps come from the provider, never from text length. */
public interface TimedAsrProvider {
    void requireConfigured();
    List<TimedCue> transcribe(Path audioFile);
}
