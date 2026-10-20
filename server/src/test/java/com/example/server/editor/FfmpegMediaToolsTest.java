package com.example.server.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class FfmpegMediaToolsTest {
    @TempDir Path dir;
    static FfmpegMediaTools tools() { return new FfmpegMediaTools(new ObjectMapper(), ""); }

    @Test void probesActualDurationAndExtractsTheRequestedJpeg() throws Exception {
        Path source = fixture(dir, false);
        assertEquals(6000, tools().probeDurationMs(source), 200);
        assertColor(tools().extractFrame(source, 500), Color.RED);
        assertColor(tools().extractFrame(source, 2500), Color.GREEN);
        assertColor(tools().extractFrame(source, 4500), Color.BLUE);
    }

    @Test void rejectsOutOfBoundsTimesMissingFilesAndAudioOnlyMedia() throws Exception {
        Path source = fixture(dir, false);
        assertThrows(IllegalArgumentException.class, () -> tools().extractFrame(source, -1));
        assertThrows(IllegalArgumentException.class, () -> tools().extractFrame(source, 6000));
        assertThrows(IllegalArgumentException.class, () -> tools().probeDurationMs(dir.resolve("missing.mp4")));
        Path audio = dir.resolve("audio.wav");
        run(List.of("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", audio.toString()));
        assertThrows(IllegalArgumentException.class, () -> tools().probeDurationMs(audio));
        Path corrupt = Files.writeString(dir.resolve("corrupt.mp4"), "not media");
        assertThrows(IllegalArgumentException.class, () -> tools().probeDurationMs(corrupt));
    }

    @Test void extractsTheLastFrameForAValidTimeAfterItsPresentationTimestamp() throws Exception {
        assertColor(tools().extractFrame(fixture(dir, false), 5999), Color.BLUE);
    }

    @Test void extractsSparseFrameStillDisplayedAtTheEnd() throws Exception {
        Path source = dir.resolve("sparse.mp4");
        run(List.of("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=160x120:r=1/5:d=10", "-c:v", "libx264", "-pix_fmt", "yuv420p", source.toString()));
        assertEquals(10000, tools().probeDurationMs(source), 100);
        assertColor(tools().extractFrame(source, 9999), Color.BLUE);
    }

    @Test void reportsMissingProbeExecutableAsInfrastructureFailure() throws Exception {
        Path source = fixture(dir, false);
        var unavailable = new FfmpegMediaTools(new ObjectMapper(), dir.resolve("missing-tools").toString());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> unavailable.probeDurationMs(source));
        assertInstanceOf(java.io.IOException.class, error.getCause());
    }

    // A real process tree, not a process mock: FFmpeg keeps the partial MP4 open until exit.
    public static class HoldingMediaProcess {
        public static void main(String[] args) throws Exception {
            Process child = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-re", "-f", "lavfi", "-i", "color=c=blue:s=160x120:r=25:d=60", "-c:v", "libx264", "-movflags", "frag_keyframe+empty_moov", "-y", args[0]).inheritIO().start();
            Files.writeString(Path.of(args[1]), ProcessHandle.current().pid() + "\n" + child.pid());
            child.waitFor();
        }
    }

    static Path fixture(Path dir, boolean audio) throws Exception {
        Path out = dir.resolve(audio ? "colors-audio.mp4" : "colors.mp4");
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=red:s=160x120:r=25:d=2", "-f", "lavfi", "-i", "color=c=green:s=160x120:r=25:d=2", "-f", "lavfi", "-i", "color=c=blue:s=160x120:r=25:d=2"));
        if (audio) args.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=6"));
        args.addAll(List.of("-filter_complex", "[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]", "-map", "[v]"));
        if (audio) args.addAll(List.of("-map", "3:a", "-c:a", "aac"));
        args.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", out.toString()));
        run(args);
        return out;
    }

    static String run(List<String> command) throws Exception {
        Path log = Files.createTempFile("media-test-", ".log");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Media fixture command timed out"); }
            String text = Files.readString(log);
            assertEquals(0, process.exitValue(), text);
            return text;
        } finally { Files.deleteIfExists(log); }
    }

    static void assertColor(byte[] jpeg, Color expected) throws Exception {
        assertTrue(jpeg.length > 2 && (jpeg[0] & 255) == 255 && (jpeg[1] & 255) == 216, "JPEG signature");
        var frame = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertNotNull(frame);
        Color actual = new Color(frame.getRGB(frame.getWidth()/2, frame.getHeight()/2));
        int channel = expected == Color.RED ? actual.getRed() : expected == Color.GREEN ? actual.getGreen() : actual.getBlue();
        assertTrue(channel > 100, "Expected " + expected + " got " + actual);
        if (expected != Color.RED) assertTrue(actual.getRed() < 30);
        if (expected != Color.GREEN) assertTrue(actual.getGreen() < 30);
        if (expected != Color.BLUE) assertTrue(actual.getBlue() < 30);
    }
}
