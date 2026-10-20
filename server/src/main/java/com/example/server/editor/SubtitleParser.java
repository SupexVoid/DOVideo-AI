package com.example.server.editor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import com.example.server.editor.EditorModels.TimedCue;
public class SubtitleParser {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_CUES = 10000;
    private static final Pattern SRT_TIME = Pattern.compile("([0-9]{2,}):([0-9]{2}):([0-9]{2}),([0-9]{3})");
    private static final Pattern VTT_TIME = Pattern.compile("(?:([0-9]{2,}):)?([0-9]{2}):([0-9]{2})\\.([0-9]{3})");
    // Only recognized subtitle markup is removed. Unknown angle-bracket text stays literal.
    private static final Pattern FORMATTING = Pattern.compile("(?i)</?(?:b|i|u|s|font|ruby|rt|v|lang|c)(?:\\.[^<>\\s.]+)*(?:\\s+[^<>]*)?>|<(?:[0-9]{2,}:)?[0-9]{2}:[0-9]{2}\\.[0-9]{3}>");

    public List<TimedCue> parse(String format, String content, long durationMs) {
        ClipRangePlanner.validateDuration(durationMs);
        if (!"srt".equals(format) && !"vtt".equals(format)) throw new IllegalArgumentException("Subtitle format must be srt or vtt");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("Subtitle content is empty");
        if (content.length() > MAX_BYTES || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Subtitle content exceeds 1 MiB");
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("\uFEFF")) normalized = normalized.substring(1);
        boolean vtt = format.equals("vtt");
        String[] blocks = normalized.strip().split("\\n[\\t ]*\\n+");
        int firstBlock = 0;
        if (vtt) {
            if (!blocks[0].split("\n", 2)[0].matches("WEBVTT(?:[ \\t].*)?")) throw new IllegalArgumentException("VTT requires a WEBVTT header");
            firstBlock = 1;
        }
        List<TimedCue> cues = new ArrayList<>();
        for (int b = firstBlock; b < blocks.length; b++) {
            String[] lines = blocks[b].strip().split("\n", -1);
            if (vtt && (lines[0].matches("NOTE(?:[ \\t].*)?") || lines[0].equals("STYLE") || lines[0].equals("REGION"))) continue;
            int index = cues.size() + 1;
            if (index > MAX_CUES) throw invalid(index, "more than 10000 cues");
            int timingLine = lines[0].contains("-->") ? 0 : 1;
            if (timingLine >= lines.length) throw invalid(index, "missing timing line");
            if (!vtt && timingLine == 1 && !lines[0].matches("[0-9]+")) throw invalid(index, "invalid SRT sequence number");
            String[] times = lines[timingLine].strip().split("[ \\t]+-->[ \\t]+", -1);
            if (times.length != 2) throw invalid(index, "invalid timing line");
            String end = vtt ? times[1].split("[ \\t]+", 2)[0] : times[1];
            long startMs = parseTime(times[0], vtt, index);
            long endMs = parseTime(end, vtt, index);
            if (startMs >= endMs || endMs > durationMs) throw invalid(index, "time range must satisfy 0 <= start < end <= duration");
            StringBuilder text = new StringBuilder();
            for (int line = timingLine + 1; line < lines.length; line++) {
                if (!text.isEmpty()) text.append('\n');
                text.append(lines[line]);
            }
            String plain = FORMATTING.matcher(text).replaceAll("").strip();
            if (plain.isBlank()) throw invalid(index, "text is empty after removing subtitle styles");
            cues.add(new TimedCue("cue-" + index, startMs, endMs, plain));
        }
        if (cues.isEmpty()) throw new IllegalArgumentException("Subtitle contains no cues");
        return List.copyOf(cues);
    }

    private long parseTime(String value, boolean vtt, int index) {
        var match = (vtt ? VTT_TIME : SRT_TIME).matcher(value);
        if (!match.matches()) throw invalid(index, "malformed timestamp: " + value);
        try {
            long hours = match.group(1) == null ? 0 : Long.parseLong(match.group(1));
            int minutes = Integer.parseInt(match.group(2));
            int seconds = Integer.parseInt(match.group(3));
            int millis = Integer.parseInt(match.group(4));
            if (minutes > 59 || seconds > 59) throw invalid(index, "minutes and seconds must be 00-59");
            long time = Math.addExact(Math.multiplyExact(hours, 3600000L), minutes * 60000L + seconds * 1000L + millis);
            if (time > ClipRangePlanner.MAX_SAFE_MS) throw invalid(index, "timestamp exceeds safe integer milliseconds");
            return time;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw invalid(index, "timestamp overflow");
        }
    }

    private IllegalArgumentException invalid(int index, String reason) {
        return new IllegalArgumentException("Subtitle cue " + index + ": " + reason);
    }
}
