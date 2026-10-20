package com.example.server.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TimedAsrAudioToolsTest {
    @TempDir Path root;
    final FfmpegMediaTools tools=new FfmpegMediaTools(new ObjectMapper(),"");
    @Test void actualWavChunksPreserveDelayedTrackSilenceAndFixedVideoOffsets() throws Exception {
        Path video=root.resolve("delayed.mkv");
        FfmpegMediaToolsTest.run(List.of("ffmpeg","-v","error","-f","lavfi","-i","color=c=blue:s=160x120:r=25:d=6","-itsoffset","2","-f","lavfi","-i","sine=frequency=440:sample_rate=16000:duration=4","-map","0:v","-map","1:a","-c:v","libx264","-c:a","pcm_s16le",video.toString()));
        Path first=root.resolve("first.wav"),next=root.resolve("next.wav");
        tools.extractAudioChunk(video,first,0,3000);tools.extractAudioChunk(video,next,3000,3000);
        assertEquals(3000,tools.probeAudioDurationMs(first),1);assertEquals(3000,tools.probeAudioDurationMs(next),1);
        byte[] pcm=tools.execute(List.of("ffmpeg","-v","error","-i",first.toString(),"-f","s16le","-acodec","pcm_s16le","pipe:1"),Duration.ofSeconds(10),200000);
        assertTrue(rms(pcm,0,16000)<1,"initial audio delay must remain silence at original video time");
        assertTrue(rms(pcm,40000,8000)>1000,"real tone must still occur after the 2s delay");
        byte[] second=tools.execute(List.of("ffmpeg","-v","error","-i",next.toString(),"-f","s16le","-acodec","pcm_s16le","pipe:1"),Duration.ofSeconds(10),200000);
        assertTrue(rms(second,0,8000)>1000,"next fixed video chunk starts within real audio");
        Path middle=root.resolve("middle.wav");tools.extractAudioChunk(video,middle,1000,3000);
        byte[] middlePcm=tools.execute(List.of("ffmpeg","-v","error","-i",middle.toString(),"-f","s16le","-acodec","pcm_s16le","pipe:1"),Duration.ofSeconds(10),200000);
        assertTrue(rms(middlePcm,0,8000)<1,"seeking before delayed audio must retain the remaining 1s silence");
        assertTrue(rms(middlePcm,24000,8000)>1000,"speech position is relative to the fixed chunk start");
    }
    @Test void rejectsNoAudioOutOfVideoExistingOutputAndDifferentDirectory() throws Exception {
        Path silent=FfmpegMediaToolsTest.fixture(root,false),output=root.resolve("chunk.wav");
        assertThrows(IllegalArgumentException.class,()->tools.extractAudioChunk(silent,output,0,1000));
        Path source=FfmpegMediaToolsTest.fixture(root,true);
        assertThrows(IllegalArgumentException.class,()->tools.extractAudioChunk(source,output,5900,1000));
        Path other=Files.createDirectory(root.resolve("other")).resolve("chunk.wav");
        assertThrows(IllegalArgumentException.class,()->tools.extractAudioChunk(source,other,0,1000));
        Files.writeString(output,"keep");
        assertThrows(IllegalArgumentException.class,()->tools.extractAudioChunk(source,output,0,1000));assertEquals("keep",Files.readString(output));
    }
    @Test void failedAudioProcessRemovesOnlyItsNewIncompleteOutput() throws Exception {
        Path source=Files.writeString(root.resolve("source.media"),"source"),output=root.resolve("chunk.wav");
        var failing=new FfmpegMediaTools(new ObjectMapper(),"") {
            @Override MediaInfo probe(Path ignored){return new MediaInfo(6000,true);}
            @Override byte[] execute(List<String> command,Duration timeout,int limit){
                try{Files.writeString(output,"incomplete");}catch(Exception e){throw new RuntimeException(e);}
                throw new IllegalStateException("simulated process exit");
            }
        };
        assertThrows(IllegalStateException.class,()->failing.extractAudioChunk(source,output,0,1000));
        assertFalse(Files.exists(output));assertEquals("source",Files.readString(source));
    }
    static double rms(byte[] pcm,int firstSample,int count){
        double square=0;
        for(int i=firstSample;i<firstSample+count;i++){int low=pcm[2*i]&255,high=pcm[2*i+1];int sample=(short)(low|(high<<8));square+=(double)sample*sample;}
        return Math.sqrt(square/count);
    }
}
