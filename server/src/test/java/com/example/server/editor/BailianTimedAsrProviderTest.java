package com.example.server.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BailianTimedAsrProviderTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    Path audio() throws Exception {
        // 100ms PCM, plus an odd-sized JUNK chunk to exercise RIFF traversal.
        ByteBuffer b=ByteBuffer.allocate(3254).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(3246).put("WAVEJUNK".getBytes()).putInt(1).put((byte)0).put((byte)0);
        b.put("fmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16);
        b.put("data".getBytes()).putInt(3200).put(new byte[3200]);
        return Files.write(root.resolve("test.wav"),b.array());
    }
    BailianTimedAsrProvider provider(Script script) {
        return new BailianTimedAsrProvider(json,"wss://dashscope.aliyuncs.com/api-ws/v1/inference","fixture-secret","paraformer-realtime-v2",script,Duration.ofMillis(500));
    }
    class Script implements WebSocket.Factory,WebSocket {
        WebSocketListener listener; Request request; String taskId; boolean canceled,closed; int frames; String run; String result="{\"begin_time\":10,\"end_time\":90,\"text\":\"原话，嗯。\",\"sentence_end\":true}";
        String mode="normal";
        public WebSocket newWebSocket(Request r,WebSocketListener l){request=r;listener=l;l.onOpen(this,new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(101).message("ok").build());return this;}
        void event(String event,String payload){listener.onMessage(this,"{\"header\":{\"event\":\""+event+"\",\"task_id\":\""+taskId+"\"},\"payload\":"+payload+"}");}
        public boolean send(String s){try {
            var data=json.readTree(s);String action=data.path("header").path("action").asText();
            if(action.equals("run-task")){run=s;taskId=data.path("header").path("task_id").asText();if(mode.equals("timeout"))return true;event("task-started","{}");}
            else {assertEquals("finish-task",action);assertTrue(frames>0);if(mode.equals("disconnect")){listener.onClosed(this,1000,"private-secret");return true;}
                if(mode.equals("failed")){event("task-failed","{}");return true;}
                if(mode.equals("malformed")){listener.onMessage(this,"invalid private-secret");return true;}
                if(mode.equals("oversize")){listener.onMessage(this,"x".repeat(2*1024*1024+1));return true;}
                if(mode.equals("transport")){listener.onFailure(this,new java.io.IOException("fixture-secret"),null);return true;}
                if(mode.equals("mismatch"))taskId="wrong";
                event("result-generated","{\"output\":{\"sentence\":{\"text\":\"partial\",\"sentence_end\":false}}}");
                event("result-generated","{\"output\":{\"sentence\":{\"heartbeat\":true}}}");
                event("result-generated","{\"output\":{\"sentence\":"+result+"}}");event("task-finished","{}");}
            return true;
        }catch(Exception e){throw new AssertionError(e);}}
        public boolean send(ByteString bytes){frames++;assertTrue(run.contains("wav"));assertTrue(bytes.size()<=3200);return true;}
        public Request request(){return request;} public long queueSize(){return 0;} public boolean close(int code,String reason){closed=true;return true;} public void cancel(){canceled=true;}
    }
    @Test void sendsAuthenticatedWavAndUsesOnlyFinalRealTimes() throws Exception {
        Script s=new Script();var cues=provider(s).transcribe(audio());
        assertEquals(1,cues.size());assertEquals(10,cues.getFirst().startMs());assertEquals(90,cues.getFirst().endMs());assertEquals("原话，嗯。",cues.getFirst().text());
        assertEquals("Bearer fixture-secret",s.request.header("Authorization"));assertTrue(s.closed||s.canceled);
        var parameters=json.readTree(s.run).path("payload").path("parameters");assertEquals("wav",parameters.path("format").asText());assertFalse(parameters.path("disfluency_removal_enabled").asBoolean(true));
    }
    @Test void refusesInvalidProtocolTimesAndSanitizesErrors() throws Exception {
        Path wav=audio();
        for(String mode:List.of("failed","malformed","mismatch","disconnect","timeout","oversize","transport")){
            Script s=new Script();s.mode=mode;var e=assertThrows(IllegalStateException.class,()->provider(s).transcribe(wav),mode);
            assertFalse(e.toString().contains("private-secret"));assertTrue(s.canceled);
            if(mode.equals("timeout"))assertEquals(0,s.frames,"audio must wait for task-started");
        }
        for(String fields:List.of("\"begin_time\":-1,\"end_time\":30","\"begin_time\":0,\"end_time\":101","\"begin_time\":20,\"end_time\":20","\"begin_time\":\"0\",\"end_time\":30","\"begin_time\":0,\"end_time\":null")){
            Script s=new Script();s.result="{"+fields+",\"text\":\"x\",\"sentence_end\":true}";assertThrows(IllegalStateException.class,()->provider(s).transcribe(wav));
        }
    }
    @Test void silenceIsEmptyAndBadWavNeverConnects() throws Exception {
        Script s=new Script();s.result="{\"text\":\"\",\"sentence_end\":true}";assertTrue(provider(s).transcribe(audio()).isEmpty());
        Script invalid=new Script();Path file=Files.writeString(root.resolve("bad.wav"),"bad");assertThrows(IllegalStateException.class,()->provider(invalid).transcribe(file));assertNull(invalid.request);
    }
    @Test void interruptedWaitCancelsAndPreservesInterrupt() throws Exception {
        Script s=new Script();s.mode="timeout";Path wav=audio();Thread.currentThread().interrupt();
        try {assertThrows(IllegalStateException.class,()->provider(s).transcribe(wav));assertTrue(Thread.currentThread().isInterrupted());assertTrue(s.canceled);}finally{Thread.interrupted();}
    }
    @Test void providerSelectionRegistersExactlyOneAdapter() {
        var context=new ApplicationContextRunner().withBean(ObjectMapper.class,ObjectMapper::new)
            .withUserConfiguration(HttpTimedAsrProvider.class,BailianTimedAsrProvider.class);
        context.run(c->{assertEquals(1,c.getBeansOfType(TimedAsrProvider.class).size());assertInstanceOf(HttpTimedAsrProvider.class,c.getBean(TimedAsrProvider.class));});
        context.withPropertyValues("editor.timed-asr.provider=bailian").run(c->{assertEquals(1,c.getBeansOfType(TimedAsrProvider.class).size());assertInstanceOf(BailianTimedAsrProvider.class,c.getBean(TimedAsrProvider.class));});
    }
    @Test void rejectsAudioOverSixtySecondsWrongFormatAndInvalidChunkLengthsBeforeConnecting() throws Exception {
        Path wav=audio();byte[] valid=Files.readAllBytes(wav);
        for(int field:List.of(30,32,34,38,42,44,50)) {
            byte[] bad=valid.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putShort(field,(short)0);
            // Field 50 is the data length: zero is invalid even though RIFF remains sized.
            Files.write(wav,bad);Script s=new Script();assertThrows(IllegalStateException.class,()->provider(s).transcribe(wav),"field "+field);assertNull(s.request);
        }
        byte[] longAudio=Arrays.copyOf(valid,54+1920032);ByteBuffer b=ByteBuffer.wrap(longAudio).order(ByteOrder.LITTLE_ENDIAN);b.putInt(4,longAudio.length-8);b.putInt(50,1920032);
        Files.write(wav,longAudio);Script s=new Script();assertThrows(IllegalStateException.class,()->provider(s).transcribe(wav));assertNull(s.request);
    }
    @Test void rejectsLongFinalText() throws Exception {
        Path wav=audio();Script longText=new Script();longText.result="{\"begin_time\":0,\"end_time\":20,\"sentence_end\":true,\"text\":\""+"x".repeat(32769)+"\"}";
        assertThrows(IllegalStateException.class,()->provider(longText).transcribe(wav));
    }
}
