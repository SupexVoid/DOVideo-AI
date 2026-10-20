package com.example.server.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import static com.example.server.editor.FfmpegMediaToolsTest.*;
import static org.junit.jupiter.api.Assertions.*;

class FfmpegMediaExporterTest {
    @TempDir Path dir;
    @Test void removesMiddleAndPreservesSourceWithAudio() throws Exception { verifyExport(true); }
    @Test void removesMiddleAndPreservesSourceWithoutAudio() throws Exception { verifyExport(false); }
    @Test void timeoutTerminatesProcessTreeBeforeRemovingPartialOutput() throws Exception { verifyAbortedExport(false); }
    @Test void interruptionTerminatesProcessTreeRemovesPartialAndPreservesInterrupt() throws Exception { verifyAbortedExport(true); }

    @Test void preservesDelayedAudioAtItsOriginalVideoTime() throws Exception {
        byte[] pcm = exportDelayedAudio(List.of(new EditorModels.ClipRange(0, 4000)), 4000);
        assertTrue(TimedAsrAudioToolsTest.rms(pcm, 0, 16000) < 10, "first video second must remain silent");
        assertTrue(TimedAsrAudioToolsTest.rms(pcm, 40000, 8000) > 1000, "tone must remain after its original 2s delay");
    }

    @Test void preservesSilentAudioWhenRangeEndsBeforeTheAudioTrackStarts() throws Exception {
        byte[] pcm = exportDelayedAudio(List.of(new EditorModels.ClipRange(0, 1000)), 1000);
        assertTrue(TimedAsrAudioToolsTest.rms(pcm, 0, 16000) < 10, "range before the audio track must contain silence");
    }

    @Test void preservesRemainingAudioDelayWhenConcatenatingNonzeroRanges() throws Exception {
        byte[] pcm = exportDelayedAudio(List.of(new EditorModels.ClipRange(1000, 4000), new EditorModels.ClipRange(5000, 6000)), 4000);
        assertTrue(TimedAsrAudioToolsTest.rms(pcm, 0, 8000) < 10, "range beginning at 1s must retain the remaining audio delay");
        assertTrue(TimedAsrAudioToolsTest.rms(pcm, 24000, 8000) > 1000, "tone must start 1s into the first selected range");
        assertTrue(TimedAsrAudioToolsTest.rms(pcm, 56000, 8000) > 1000, "second selected range must retain its tone");
    }

    private byte[] exportDelayedAudio(List<EditorModels.ClipRange> ranges, long durationMs) throws Exception {
        Path source = dir.resolve("delayed.mkv");
        run(List.of("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=160x120:r=25:d=6", "-itsoffset", "2", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000:duration=4", "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-c:a", "pcm_s16le", source.toString()));
        Path job = Files.createDirectory(dir.resolve("delayed-job")), output = job.resolve("output.mp4");
        new FfmpegMediaExporter(tools(), dir).export(source, ranges, output);
        assertEquals(durationMs, tools().probeDurationMs(output), 100);
        byte[] pcm = tools().execute(List.of("ffmpeg", "-v", "error", "-i", output.toString(), "-map", "0:a:0", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-f", "s16le", "pipe:1"), Duration.ofSeconds(10), 200000);
        assertTrue(pcm.length >= durationMs * 32, "decoded audio must span the selected video duration");
        return pcm;
    }

    void verifyAbortedExport(boolean interrupt) throws Exception {
        Path source = fixture(dir, false);
        Path task = Files.createDirectories(dir.resolve("aborted-job"));
        Path pids = dir.resolve("process-ids.txt");
        var realTools = new FfmpegMediaTools(new com.fasterxml.jackson.databind.ObjectMapper(), "") {
            @Override byte[] execute(List<String> command, Duration timeout, int stdoutLimit) {
                if (!command.contains("-filter_complex") && !command.contains("-/filter_complex")) return super.execute(command, timeout, stdoutLimit);
                String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                return super.execute(List.of(java, "-Xms16m", "-Xmx64m", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), HoldingMediaProcess.class.getName(), command.getLast(), pids.toString()), Duration.ofSeconds(interrupt ? 30 : 2), stdoutLimit);
            }
        };
        var exporter = new FfmpegMediaExporter(realTools, dir);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try { exporter.export(source, List.of(new EditorModels.ClipRange(0, 2000)), task.resolve("output.mp4")); }
            catch (Throwable failure) { error.set(failure); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            worker.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while ((!Files.exists(pids) || Files.size(pids) == 0) && worker.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(pids), "Real process tree must start");
            if (interrupt) worker.interrupt();
            worker.join(12000);
            assertFalse(worker.isAlive(), "Aborted export must return within teardown bound");
            assertInstanceOf(IllegalStateException.class, error.get());
            assertTrue(error.get().getMessage().contains(interrupt ? "interrupted" : "timed out"));
            assertEquals(interrupt, interrupted.get());
            for (String pid : Files.readAllLines(pids)) assertFalse(ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false), "Parent and child must be dead before export returns");
            try (var paths = Files.list(task)) { assertEquals(0, paths.count(), "Failed job must leave no partial output"); }
        } finally {
            worker.interrupt();
            worker.join(1000);
            if (Files.exists(pids)) for (String pid : Files.readAllLines(pids)) {
                var handle = ProcessHandle.of(Long.parseLong(pid));
                if (handle.isPresent() && handle.get().isAlive()) { handle.get().destroyForcibly(); handle.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS); }
            }
        }
    }

    void verifyExport(boolean audio) throws Exception {
        Path source = fixture(dir, audio);
        byte[] original = Files.readAllBytes(source);
        Path task = Files.createDirectories(dir.resolve("job"));
        Path output = task.resolve("output.mp4");
        new FfmpegMediaExporter(tools(), dir).export(source, List.of(new EditorModels.ClipRange(0, 2000), new EditorModels.ClipRange(4000, 6000)), output);
        assertEquals(4000, tools().probeDurationMs(output), 200);
        for (long at : new long[]{200, 1000, 1800}) assertColor(tools().extractFrame(output, at), Color.RED);
        for (long at : new long[]{2200, 3000, 3800}) assertColor(tools().extractFrame(output, at), Color.BLUE);
        String streams = run(List.of("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,codec_type", "-of", "json", output.toString()));
        assertTrue(streams.contains("h264"));
        assertEquals(audio, streams.contains("aac"));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertEquals(1, Files.list(task).count(), "No partial artifacts");
    }

    @Test void rejectsInvalidRangesUnsafeDestinationAndExistingOutput() throws Exception {
        Path source = fixture(dir, false);
        Path root = Files.createDirectories(dir.resolve("tasks"));
        Path job = Files.createDirectories(root.resolve("job"));
        var exporter = new FfmpegMediaExporter(tools(), root);
        for (var ranges : List.of(List.<EditorModels.ClipRange>of(), List.of(new EditorModels.ClipRange(-1, 100)), List.of(new EditorModels.ClipRange(0, 0)), List.of(new EditorModels.ClipRange(0, 7000)), List.of(new EditorModels.ClipRange(4000, 5000), new EditorModels.ClipRange(0, 1000)), List.of(new EditorModels.ClipRange(0, 3000), new EditorModels.ClipRange(2000, 4000)))) {
            assertThrows(IllegalArgumentException.class, () -> exporter.export(source, ranges, job.resolve("output.mp4")));
        }
        assertThrows(IllegalArgumentException.class, () -> exporter.export(source, List.of(new EditorModels.ClipRange(0, 1000)), dir.resolve("outside.mp4")));
        assertThrows(IllegalArgumentException.class, () -> exporter.export(source, List.of(new EditorModels.ClipRange(0, 1000)), source));
        Path existing = Files.writeString(job.resolve("existing.mp4"), "keep");
        assertThrows(IllegalArgumentException.class, () -> exporter.export(source, List.of(new EditorModels.ClipRange(0, 1000)), existing));
        assertEquals("keep", Files.readString(existing));
    }

    @Test void thousandRangeGraphCrossesActualProcessBoundaryWithBoundedArgumentsAndScopedCleanup() throws Exception {
        Path source=Files.writeString(dir.resolve("source.media"),"source preserved");
        Path task=Files.createDirectory(dir.resolve("many-ranges")),output=task.resolve("output.mp4");
        AtomicReference<String> childReport=new AtomicReference<>();
        var processTools=new FfmpegMediaTools(new com.fasterxml.jackson.databind.ObjectMapper(),"") {
            @Override MediaInfo probe(Path path) {return new MediaInfo(path.equals(source)?2000000:1000000,true);}
            @Override byte[] execute(List<String> command,Duration timeout,int stdoutLimit) {
                List<String> child=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xms16m","-Xmx64m","-cp",
                    System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),FilterFileProcess.class.getName()));
                child.addAll(command.subList(1,command.size()));
                byte[] report=super.execute(child,timeout,stdoutLimit);
                childReport.set(new String(report,java.nio.charset.StandardCharsets.UTF_8));return report;
            }
        };
        List<EditorModels.ClipRange> ranges=new ArrayList<>();
        for(int i=0;i<1000;i++)ranges.add(new EditorModels.ClipRange(i*2000L,i*2000L+1000));
        new FfmpegMediaExporter(processTools,dir).export(source,ranges,output);
        assertTrue(childReport.get().startsWith("GRAPH_OK:1000:"),childReport.get());
        assertEquals("verified process output",Files.readString(output));
        assertEquals("source preserved",Files.readString(source));
        try(var files=Files.list(task)){assertEquals(List.of(output),files.toList(),"Only completed output may remain");}
    }

    @Test void retriesLegacyFileOptionOnlyWhenModernOptionIsUnsupported() throws Exception {
        Path source = Files.writeString(dir.resolve("legacy-source"), "source");
        Path job = Files.createDirectory(dir.resolve("legacy-job"));
        Path output = job.resolve("result.mp4");
        AtomicInteger calls = new AtomicInteger();
        var legacyTools = new FfmpegMediaTools(new com.fasterxml.jackson.databind.ObjectMapper(), "") {
            @Override MediaInfo probe(Path path) { return new MediaInfo(1000, false); }
            @Override byte[] execute(List<String> command, Duration timeout, int limit) {
                if (calls.incrementAndGet() == 1) {
                    assertTrue(command.contains("-/filter_complex"));
                    throw new IllegalStateException("Media process failed (exit 8): Unrecognized option '/filter_complex'.");
                }
                assertFalse(command.contains("-/filter_complex"));
                int index = command.indexOf("-filter_complex_script");
                assertTrue(index > 0);
                try {
                    assertTrue(Files.readString(Path.of(command.get(index + 1))).contains("concat=n=1"));
                    Files.writeString(Path.of(command.getLast()), "legacy encoded");
                } catch (java.io.IOException e) { throw new RuntimeException(e); }
                return new byte[0];
            }
        };
        new FfmpegMediaExporter(legacyTools, dir).export(source, List.of(new EditorModels.ClipRange(0, 1000)), output);
        assertEquals(2, calls.get());
        assertEquals("legacy encoded", Files.readString(output));
        try (var files = Files.list(job)) { assertEquals(List.of(output), files.toList()); }
    }

    @Test void doesNotRetryUnrelatedEncoderErrors() throws Exception {
        Path source = Files.writeString(dir.resolve("bad-source"), "source");
        Path job = Files.createDirectory(dir.resolve("bad-job"));
        AtomicInteger calls = new AtomicInteger();
        var broken = new FfmpegMediaTools(new com.fasterxml.jackson.databind.ObjectMapper(), "") {
            @Override MediaInfo probe(Path path) { return new MediaInfo(1000, false); }
            @Override byte[] execute(List<String> command, Duration timeout, int limit) {
                calls.incrementAndGet();
                throw new IllegalStateException("Media process failed (exit 1): encoder unavailable");
            }
        };
        assertThrows(IllegalStateException.class, () -> new FfmpegMediaExporter(broken, dir).export(source,
            List.of(new EditorModels.ClipRange(0, 1000)), job.resolve("result.mp4")));
        assertEquals(1, calls.get());
        try (var files = Files.list(job)) { assertEquals(0, files.count()); }
    }

    /** Lightweight real subprocess validates the generated graph without encoding a thousand streams. */
    public static class FilterFileProcess {
        public static void main(String[] args) throws Exception {
            List<String> command=Arrays.asList(args);
            int flag=command.indexOf("-/filter_complex");
            if(flag<0)throw new IllegalStateException("Filter must be read from a file");
            int argumentChars=command.stream().mapToInt(String::length).sum();
            if(argumentChars>=8192 || command.stream().anyMatch(a->a.length()>4096))throw new IllegalStateException("Command arguments grew with ranges");
            Path script=Path.of(command.get(flag+1)),output=Path.of(command.getLast());
            if(!script.getParent().toRealPath().equals(output.getParent().toRealPath()))throw new IllegalStateException("Script outside private job directory");
            String graph=Files.readString(script,java.nio.charset.StandardCharsets.UTF_8);
            if(graph.length()<100000 || graph.split("\\[0:v:0\\]trim=",-1).length-1!=1000
                    || graph.split("\\[0:a:0\\]atrim=",-1).length-1!=1000
                    || !graph.contains("trim=start=0.000:end=1.000")
                    || !graph.contains("trim=start=1998.000:end=1999.000")
                    || !graph.endsWith("[v999][a999]concat=n=1000:v=1:a=1[v][a]"))
                throw new IllegalStateException("Incomplete generated graph");
            Files.writeString(output,"verified process output");
            System.out.print("GRAPH_OK:1000:"+graph.length()+":"+argumentChars);
        }
    }
}
