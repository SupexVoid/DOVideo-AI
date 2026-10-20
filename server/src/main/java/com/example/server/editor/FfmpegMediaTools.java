package com.example.server.editor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Component
public class FfmpegMediaTools {
    private final ObjectMapper mapper;
    private final String ffmpegDir;
    public FfmpegMediaTools(ObjectMapper mapper, @Value("${tool.ffmpeg.dir:}") String ffmpegDir) {
        this.mapper = Objects.requireNonNull(mapper);
        this.ffmpegDir = ffmpegDir == null ? "" : ffmpegDir.trim();
    }

    public long probeDurationMs(Path source) { return probe(source).durationMs(); }

    record MediaInfo(long durationMs, boolean audio) {}

    MediaInfo probe(Path source) {
        if (source == null || !Files.isRegularFile(source)) throw new IllegalArgumentException("Source media does not exist");
        byte[] json;
        try {
            json = execute(List.of(executable("ffprobe"), "-v", "error", "-show_entries", "format=duration:stream=codec_type,duration,width,height,avg_frame_rate", "-of", "json", source.toAbsolutePath().toString()), Duration.ofSeconds(30), 1024 * 1024);
        } catch (ProcessExitException e) {
            throw new IllegalArgumentException("Cannot probe source video", e);
        }
        try {
            var data = mapper.readTree(json);
            boolean video = false, audio = false;
            double videoSeconds = Double.NaN;
            for (var stream : data.path("streams")) {
                if ("video".equals(stream.path("codec_type").asText())) {
                    video = true;
                    double seconds = stream.path("duration").asDouble(Double.NaN);
                    if (Double.isFinite(seconds) && seconds > 0) videoSeconds = Math.max(Double.isNaN(videoSeconds) ? 0 : videoSeconds, seconds);
                }
                if ("audio".equals(stream.path("codec_type").asText())) audio = true;
            }
            double seconds = Double.isNaN(videoSeconds) ? data.path("format").path("duration").asDouble(Double.NaN) : videoSeconds;
            if (!video || !Double.isFinite(seconds) || seconds <= 0 || seconds * 1000 > Long.MAX_VALUE) throw new IllegalArgumentException("A video stream with a valid positive duration is required");
            long duration = Math.round(seconds * 1000);
            if (duration <= 0) throw new IllegalArgumentException("Video duration is below one millisecond");
            return new MediaInfo(duration, audio);
        } catch (IOException e) { throw new IllegalArgumentException("Invalid FFprobe JSON", e); }
    }

    public byte[] extractFrame(Path source, long atMs) {
        long duration = probeDurationMs(source);
        if (atMs < 0 || atMs >= duration) throw new IllegalArgumentException("Frame time must be within the video duration");
        byte[] jpeg = execute(List.of(executable("ffmpeg"), "-nostdin", "-v", "error", "-ss", seconds(atMs), "-i", source.toAbsolutePath().toString(), "-map", "0:v:0", "-frames:v", "1", "-f", "image2pipe", "-c:v", "mjpeg", "-pix_fmt", "yuvj420p", "pipe:1"), Duration.ofSeconds(30), 16 * 1024 * 1024);
        // Try a short tail first. Sparse videos may display a much earlier frame, so fall
        // back to streaming from the beginning. A single overwritten JPEG bounds storage
        // and memory; the existing process timeout bounds expensive decoding.
        if (jpeg.length == 0) jpeg = lastFrameBefore(source, atMs, Math.max(0, atMs - 1000));
        if (jpeg.length == 0 && atMs > 1000) jpeg = lastFrameBefore(source, atMs, 0);
        if (jpeg.length < 3 || (jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216) throw new IllegalStateException("FFmpeg returned no JPEG frame");
        return jpeg;
    }

    private byte[] lastFrameBefore(Path source, long atMs, long seekMs) {
        Path image = null;
        Throwable failure = null;
        try {
            image = Files.createTempFile("dovideo-editor-frame-", ".jpg");
            execute(List.of(executable("ffmpeg"), "-nostdin", "-v", "error", "-y", "-ss", seconds(seekMs), "-i", source.toAbsolutePath().toString(), "-map", "0:v:0", "-t", seconds(atMs - seekMs + 1), "-vf", "select=lte(t\\," + seconds(atMs - seekMs) + ")", "-fps_mode", "passthrough", "-update", "1", "-c:v", "mjpeg", "-pix_fmt", "yuvj420p", "-f", "image2", image.toString()), Duration.ofSeconds(30), 64 * 1024);
            try (InputStream stream = Files.newInputStream(image)) {
                byte[] jpeg = stream.readNBytes(16 * 1024 * 1024 + 1);
                if (jpeg.length > 16 * 1024 * 1024) throw new IllegalStateException("JPEG frame exceeds limit");
                return jpeg;
            }
        } catch (IOException e) {
            var wrapped = new IllegalStateException("Cannot capture fallback JPEG frame", e);
            failure = wrapped;
            throw wrapped;
        } catch (RuntimeException | Error e) { failure = e; throw e; }
        finally {
            if (image != null) try { Files.deleteIfExists(image); }
            catch (IOException e) {
                var cleanup = new IllegalStateException("Cannot remove temporary JPEG frame", e);
                if (failure != null) failure.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }

    /** Caller supplies two files within its private job directory. No overwrite or arbitrary output path. */
    public void extractAudioChunk(Path source,Path output,long startMs,long durationMs) {
        if(source==null||output==null||startMs<0||durationMs<=0||durationMs>60000)throw new IllegalArgumentException("Invalid audio chunk");
        Path input=source.toAbsolutePath().normalize(),target=output.toAbsolutePath().normalize();
        try {
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)||!input.getParent().equals(target.getParent())
                    ||Files.isSymbolicLink(input)||Files.isSymbolicLink(target.getParent())
                    ||!input.getParent().toRealPath().equals(target.getParent().toRealPath()))throw new IllegalArgumentException("Audio output must be a new file in the source job directory");
            MediaInfo info=probe(input);
            if(!info.audio()||startMs>=info.durationMs()||durationMs>info.durationMs()-startMs)throw new IllegalArgumentException("Audio chunk must be within a video with audio");
            try {
                // Accurate input seeking bounds decoding for long lectures. copyts/start_at_zero
                // keep timestamps on the normalized video clock; subtract the fixed chunk offset
                // before filling any delayed-track silence. Never align using the first speech sample.
                String filter="asetpts=PTS-"+seconds(startMs)+"/TB,aresample=16000:async=1:first_pts=0,atrim=duration="+seconds(durationMs)
                    +",asetpts=PTS-STARTPTS,apad,atrim=duration="+seconds(durationMs);
                execute(List.of(executable("ffmpeg"),"-nostdin","-v","error","-n","-ss",seconds(startMs),"-copyts","-start_at_zero","-i",input.toString(),
                    "-map","0:a:0","-vn","-af",filter,"-ac","1","-ar","16000","-c:a","pcm_s16le","-f","wav",target.toString()),Duration.ofSeconds(60),64*1024);
                if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||Files.size(target)>3L*1024*1024)throw new IllegalStateException("Audio output invalid");
            } catch(RuntimeException|IOException error) {
                try{Files.deleteIfExists(target);}catch(IOException cleanup){error.addSuppressed(cleanup);}
                if(error instanceof RuntimeException runtime)throw runtime;
                throw new IllegalStateException("Audio output unavailable",error);
            }
        } catch(IOException error){throw new IllegalStateException("Audio job directory unavailable",error);}
    }

    public long probeAudioDurationMs(Path audio) {
        if(audio==null||!Files.isRegularFile(audio,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Audio file missing");
        byte[] bytes=execute(List.of(executable("ffprobe"),"-v","error","-show_entries","format=duration:stream=codec_type","-of","json",audio.toAbsolutePath().toString()),Duration.ofSeconds(30),64*1024);
        try {
            var data=mapper.readTree(bytes);boolean found=false;
            for(var stream:data.path("streams"))if("audio".equals(stream.path("codec_type").asText()))found=true;
            double seconds=data.path("format").path("duration").asDouble(Double.NaN);
            if(!found||!Double.isFinite(seconds)||seconds<=0||seconds>61)throw new IllegalStateException("Invalid audio duration");
            return Math.round(seconds*1000);
        } catch(IOException invalid){throw new IllegalStateException("Invalid audio probe",invalid);}
    }

    String executable(String name) { return ffmpegDir.isBlank() ? name : Path.of(ffmpegDir).resolve(name + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : "")).toString(); }
    static String seconds(long ms) { return String.format(Locale.ROOT, "%.3f", ms / 1000.0); }

    // Drain both pipes concurrently; retain bounded bytes and discard excess to avoid deadlocks.
    byte[] execute(List<String> command, Duration timeout, int stdoutLimit) {
        Process process = null;
        Thread outThread = null, errThread = null;
        Throwable failure = null;
        try {
            process = new ProcessBuilder(command).start();
            Capture stdout = new Capture(process.getInputStream(), stdoutLimit);
            Capture stderr = new Capture(process.getErrorStream(), 64 * 1024);
            outThread = Thread.ofVirtual().start(stdout);
            errThread = Thread.ofVirtual().start(stderr);
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IllegalStateException("Media process timed out");
                outThread.join(2000);
                errThread.join(2000);
                if (outThread.isAlive() || errThread.isAlive()) throw new IllegalStateException("Media process pipes did not close");
                if (process.exitValue() != 0) throw new ProcessExitException("Media process failed (exit " + process.exitValue() + "): " + stderr.text());
                if (stdout.failure != null || stderr.failure != null || stdout.overflow) throw new IllegalStateException("Media process output unavailable or exceeds limit");
                return stdout.bytes.toByteArray();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            var wrapped = new IllegalStateException("Media process interrupted", e);
            failure = wrapped;
            throw wrapped;
        } catch (IOException e) {
            var wrapped = new IllegalStateException("Media executable unavailable or failed", e);
            failure = wrapped;
            throw wrapped;
        } catch (RuntimeException | Error e) { failure = e; throw e; }
        finally {
            if (process != null) {
                RuntimeException cleanup = finishProcess(process, outThread, errThread);
                if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup); else throw cleanup; }
            }
        }
    }

    private static RuntimeException finishProcess(Process process, Thread outThread, Thread errThread) {
        boolean[] interrupted = {Thread.interrupted()};
        RuntimeException failure = null;
        try {
            // Kill/wait descendants while the parent can still reap them and before closing
            // inherited pipes. Closing a pipe first can block while an orphan holds it open.
            List<ProcessHandle> descendants = process.descendants().toList();
            for (var child : descendants) if (child.isAlive()) child.destroyForcibly();
            long childDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            for (var child : descendants) if (!awaitExit(child, childDeadline, interrupted)) failure = new IllegalStateException("Media descendant did not terminate");
            if (process.isAlive()) process.destroyForcibly();
            if (!awaitExit(process.toHandle(), System.nanoTime() + TimeUnit.SECONDS.toNanos(3), interrupted)) failure = new IllegalStateException("Media process did not terminate");
            // Only close pipes after confirmed termination to avoid blocking in close while
            // a reader holds the pipe monitor. Capture tasks normally finish at EOF.
            long captureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            for (Thread thread : new Thread[]{outThread, errThread}) if (thread != null) {
                while (thread.isAlive() && System.nanoTime() < captureDeadline) {
                    try { thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(captureDeadline - System.nanoTime()))); }
                    catch (InterruptedException e) { interrupted[0] = true; }
                }
                if (thread.isAlive()) failure = new IllegalStateException("Media capture task did not terminate");
            }
            if ((outThread == null || !outThread.isAlive()) && (errThread == null || !errThread.isAlive())) {
                try { process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close(); }
                catch (IOException e) { failure = new IllegalStateException("Cannot close media process pipes", e); }
            }
            return failure;
        } catch (RuntimeException e) { return e; }
        finally { if (interrupted[0]) Thread.currentThread().interrupt(); }
    }

    private static boolean awaitExit(ProcessHandle handle, long deadline, boolean[] interrupted) {
        while (handle.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            try { handle.onExit().get(remaining, TimeUnit.NANOSECONDS); }
            catch (InterruptedException e) { interrupted[0] = true; }
            catch (TimeoutException e) { return false; }
            catch (ExecutionException e) { throw new IllegalStateException("Cannot await media process termination", e); }
        }
        return true;
    }

    // Probe maps decoder rejection to bad input; startup/timeout/interruption must remain
    // infrastructure failures so the service can return its unavailable response.
    private static final class ProcessExitException extends IllegalStateException {
        ProcessExitException(String message) { super(message); }
    }

    private static final class Capture implements Runnable {
        final InputStream stream;
        final int limit;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        volatile IOException failure;
        boolean overflow;
        Capture(InputStream stream, int limit) { this.stream = stream; this.limit = limit; }
        public void run() {
            try {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    int keep = Math.min(count, limit - bytes.size());
                    if (keep > 0) bytes.write(buffer, 0, keep);
                    if (keep < count) overflow = true;
                }
            } catch (IOException e) { failure = e; }
        }
        String text() { return bytes.toString(java.nio.charset.StandardCharsets.UTF_8); }
    }
}
