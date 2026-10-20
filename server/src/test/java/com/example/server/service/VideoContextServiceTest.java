package com.example.server.service;
import com.example.server.dto.*;
import com.example.server.utils.*;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoContextServiceTest {
    @Test void actualOcrFrameIsRetainedInsideCueAndInSpeechGap(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var source=dir.resolve("source.mp4");
        var log=dir.resolve("ffmpeg.log");
        var process=new ProcessBuilder("ffmpeg","-v","error","-f","lavfi","-i","testsrc2=s=160x120:r=25:d=1","-c:v","libx264","-pix_fmt","yuv420p",source.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue(),java.nio.file.Files.readString(log));}
        finally {if(process.isAlive())process.destroyForcibly();}
        var old=mock(SegmentedTranscriptionService.class);var minio=mock(MinioUtils.class);var ocr=mock(OcrUtils.class);
        when(minio.readableSource("source")).thenReturn(source.toString());when(ocr.recognize(any(java.io.File.class))).thenReturn("visible slide");
        when(minio.uploadLocalFile(any(java.io.File.class),anyString(),eq("evidence-frames"))).thenReturn("managed-frame");
        var asr=mock(ThreadPoolTaskExecutor.class);var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.initialize();
        try {
            var service=new VideoContextService(old,ocr,minio,asr,executor,mock(AgentTelemetry.class));
            var inside=service.build("source","goal",null,List.of(new TranscriptSegment(0,800,"spoken")));
            assertEquals(List.of(new VideoContext.VideoSegment(0,800,"spoken",List.of("visible slide"),List.of("managed-frame"))),inside.segments());
            var gap=service.build("source","goal",null,List.of(new TranscriptSegment(120,800,"spoken")));
            assertEquals(List.of(new VideoContext.VideoSegment(0,1,"",List.of("visible slide"),List.of("managed-frame")),new VideoContext.VideoSegment(120,800,"spoken",List.of(),List.of())),gap.segments());
            verifyNoInteractions(old,asr);
        } finally {executor.shutdown();}
    }
    @Test void suppliedActualCuesSurviveOcrFailureWithoutCallingLegacyAsr() {
        var old=mock(SegmentedTranscriptionService.class);var minio=mock(MinioUtils.class);
        var asr=mock(ThreadPoolTaskExecutor.class);var ocr=mock(ThreadPoolTaskExecutor.class);
        when(minio.readableSource("source")).thenReturn("source");
        when(asr.submit(any(Callable.class))).thenThrow(new RejectedExecutionException("old must not run"));
        when(ocr.submit(any(Callable.class))).thenThrow(new RejectedExecutionException("OCR offline"));
        var service=new VideoContextService(old,mock(OcrUtils.class),minio,asr,ocr,mock(AgentTelemetry.class));
        var cues=List.of(new TranscriptSegment(120,2340,"first"),new TranscriptSegment(3100,4300,"second"));
        VideoContext result=assertDoesNotThrow(()->service.build("source","goal",null,cues));
        assertEquals(List.of(new VideoContext.VideoSegment(120,2340,"first",List.of(),List.of()),new VideoContext.VideoSegment(3100,4300,"second",List.of(),List.of())),result.segments());
        assertEquals("first\nsecond",result.transcriptText());verifyNoInteractions(old,asr);verify(ocr).submit(any(Callable.class));
    }
}
