package com.example.server.editor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SubtitleParserTest {
    private final SubtitleParser parser = new SubtitleParser();
    private String srt(String start, String end, String text) { return "1\n" + start + " --> " + end + "\n" + text; }
    @Test void parsesBomCrlfMultilineSrtAndStableUniqueIds() {
        String content = "\uFEFF1\r\n00:00:00,001 --> 00:00:02,000\r\n第一行\r\nsecond\r\n\r\n1\r\n00:00:01,000 --> 00:00:03,000\r\nother";
        var cues = parser.parse("srt", content, 3000);
        assertEquals(2, cues.size());
        assertEquals(1, cues.getFirst().startMs());
        assertEquals(2000, cues.getFirst().endMs());
        assertEquals("第一行\nsecond", cues.getFirst().text());
        assertNotEquals(cues.getFirst().id(), cues.getLast().id());
        assertEquals(cues, parser.parse("srt", content, 3000));
    }
    @Test void parsesVttIdentifiersSettingsAndMetadata() {
        var cues = parser.parse("vtt", "WEBVTT title\nKind: captions\n\nNOTE remark\nignored\n\nSTYLE\n::cue { color: red }\n\nintro\n00:00.010 --> 00:02.500 align:start\n<v Alice><c.red>Hello</c></v> <00:01.000>world", 3000);
        assertEquals(1, cues.size());
        assertEquals(10, cues.getFirst().startMs());
        assertEquals(2500, cues.getFirst().endMs());
        assertEquals("Hello world", cues.getFirst().text());
    }
    @Test void stripsFormattingAndPreservesSafeLiteralText() {
        var cues = parser.parse("srt", srt("00:00:00,000", "00:00:01,000", "<b>bold</b> <i>italic</i> 2 < 3 &lt;b&gt; <script>alert('x')</script>"), 1000);
        assertEquals(1, cues.size());
        assertEquals("bold italic 2 < 3 &lt;b&gt; <script>alert('x')</script>", cues.getFirst().text());
    }
    @Test void stripsClassAnnotatedKnownVttTagsAndPreservesUnknownLiterals() {
        var cues = parser.parse("vtt", "WEBVTT\n\n00:00.000 --> 00:01.000\n<b.warning>Hello</b> <v.speaker Alice>world</v> <i.soft.emphasis>quiet</i> <custom.warning>literal</custom> 2 < 3", 1000);
        assertEquals("Hello world quiet <custom.warning>literal</custom> 2 < 3", cues.getFirst().text());
    }
    @Test void rejectsMalformedOverflowAndOutOfDurationTimesWithCueIndex() {
        for (var pair : new String[][] {{"00:60:00,000", "00:00:01,000"}, {"00:00:00,000", "00:00:00,000"}, {"00:00:02,000", "00:00:01,000"}, {"-00:00:01,000", "00:00:01,000"}, {"999999999999999999999:00:00,000", "00:00:01,000"}, {"00:00:00,000", "00:00:02,000"}, {"00:00:00,00", "00:00:01,000"}}) {
            var error = assertThrows(IllegalArgumentException.class, () -> parser.parse("srt", srt(pair[0], pair[1], "text"), 1000));
            assertTrue(error.getMessage().contains("1"), error.getMessage());
        }
    }
    @Test void rejectsBlankTextAndDoesNotSilentlySkipBadCue() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("srt", srt("00:00:00,000", "00:00:01,000", "<b></b>"), 1000));
        var error = assertThrows(IllegalArgumentException.class, () -> parser.parse("srt", srt("00:00:00,000", "00:00:01,000", "ok") + "\n\n2\nbad timing\ntext", 1000));
        assertTrue(error.getMessage().contains("2"));
    }
    @Test void enforcesFormatBodyCueAndDurationLimits() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("ass", "text", 1000));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("srt", "", 1000));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("vtt", "00:00.000 --> 00:01.000\ntext", 1000));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("srt", "中".repeat(350000), 1000));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("srt", (srt("00:00:00,000", "00:00:01,000", "text") + "\n\n").repeat(10001), 1000));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("srt", srt("00:00:00,000", "00:00:01,000", "text"), 9007199254740992L));
    }
}
