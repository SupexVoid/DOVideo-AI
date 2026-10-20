package com.example.server.editor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

@Component
public class FfmpegMediaExporter {
    private final FfmpegMediaTools tools;
    private final Path taskRoot;

    @Autowired
    public FfmpegMediaExporter(FfmpegMediaTools tools, @Value("${editor.task-root:${java.io.tmpdir}/dovideo-editor}") String taskRoot) { this(tools, Path.of(taskRoot)); }
    public FfmpegMediaExporter(FfmpegMediaTools tools, Path taskRoot) {
        this.tools = Objects.requireNonNull(tools);
        this.taskRoot = Objects.requireNonNull(taskRoot).toAbsolutePath().normalize();
    }

    public void export(Path source, List<EditorModels.ClipRange> ranges, Path output) {
        var media = tools.probe(source);
        if (ranges == null || ranges.isEmpty() || ranges.size() > 1000) throw new IllegalArgumentException("One to 1000 clip ranges are required");
        ranges = List.copyOf(ranges);
        long previousEnd = -1, expectedDuration = 0;
        for (var range : ranges) {
            if (range == null || range.startMs() < 0 || range.endMs() <= range.startMs() || range.endMs() > media.durationMs() || range.startMs() < previousEnd) throw new IllegalArgumentException("Clip ranges must be nonempty, ordered, nonoverlapping and within the video duration");
            expectedDuration += range.endMs() - range.startMs();
            previousEnd = range.endMs();
        }
        Path partial = null, filterScript = null;
        Throwable failure = null;
        try {
            if (output == null) throw new IllegalArgumentException("Output is required");
            Path destination = output.toAbsolutePath().normalize();
            Path root = taskRoot.toRealPath();
            Path parent = destination.getParent().toRealPath();
            if (!parent.startsWith(root) || parent.equals(root) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS) || destination.equals(source.toAbsolutePath().normalize())) throw new IllegalArgumentException("Output must be a new file in an editor job directory");
            partial = Files.createTempFile(parent, "export-", ".partial.mp4");
            StringBuilder filter = new StringBuilder();
            for (int i = 0; i < ranges.size(); i++) {
                var range = ranges.get(i);
                String times = "start=" + FfmpegMediaTools.seconds(range.startMs()) + ":end=" + FfmpegMediaTools.seconds(range.endMs());
                filter.append("[0:v:0]trim=").append(times).append(",setpts=PTS-STARTPTS[v").append(i).append("];");
                // Keep the audio on the requested video clock, including silence before
                // a delayed track starts and after it ends, for every concat segment.
                if (media.audio()) filter.append("[0:a:0]atrim=").append(times)
                    .append(",asetpts=PTS-").append(FfmpegMediaTools.seconds(range.startMs())).append("/TB,aresample=async=1:first_pts=0,apad,atrim=duration=")
                    .append(FfmpegMediaTools.seconds(range.endMs() - range.startMs())).append("[a").append(i).append("];");
            }
            for (int i = 0; i < ranges.size(); i++) {
                filter.append("[v").append(i).append("]");
                if (media.audio()) filter.append("[a").append(i).append("]");
            }
            filter.append("concat=n=").append(ranges.size()).append(":v=1:a=").append(media.audio() ? "1[v][a]" : "0[v]");
            // FFmpeg's file-backed option keeps the Windows command line bounded even at 1000 ranges.
            filterScript = Files.createTempFile(parent, "export-filter-", ".txt");
            Files.writeString(filterScript, filter, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(List.of(tools.executable("ffmpeg"), "-nostdin", "-v", "error", "-y", "-i", source.toAbsolutePath().toString(), "-/filter_complex", filterScript.toString(), "-map", "[v]"));
            if (media.audio()) command.addAll(List.of("-map", "[a]", "-c:a", "aac"));
            else command.add("-an");
            command.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-f", "mp4", partial.toString()));
            tools.execute(command, Duration.ofMinutes(10), 64 * 1024);
            var result = tools.probe(partial);
            if (Math.abs(result.durationMs() - expectedDuration) > 200 || result.audio() != media.audio()) throw new IllegalStateException("Exported media failed duration or audio validation");
            // The worker owns this private job directory. Never replace an existing result.
            Files.move(partial, parent.resolve(destination.getFileName()));
            partial = null;
        } catch (IOException e) {
            var wrapped = new IllegalArgumentException("Editor job output path is unavailable", e);
            failure = wrapped;
            throw wrapped;
        } catch (RuntimeException | Error e) { failure = e; throw e; }
        finally {
            IllegalStateException cleanupFailure = null;
            for (Path created : Arrays.asList(partial, filterScript)) {
                if (created == null) continue;
                try {
                    Path realParent = created.getParent().toRealPath();
                    Path realRoot = taskRoot.toRealPath();
                    if (!realParent.startsWith(realRoot) || realParent.equals(realRoot)) throw new IOException("Refusing cleanup outside editor job directory");
                    Files.deleteIfExists(created);
                } catch (IOException | SecurityException e) {
                    var cleanup = new IllegalStateException("Cannot remove editor export temporary file", e);
                    if (failure != null) failure.addSuppressed(cleanup);
                    else if (cleanupFailure == null) cleanupFailure = cleanup;
                    else cleanupFailure.addSuppressed(cleanup);
                }
            }
            if (cleanupFailure != null) throw cleanupFailure;
        }
    }
}
