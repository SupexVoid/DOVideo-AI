package com.example.server.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Local HTTP fixture only: these are parser/protocol tests, never ASR quality evidence. */
class HttpTimedAsrProviderTest {
    @TempDir Path root;
    HttpServer server;
    String response="{\"segments\":[{\"start\":0.12,\"end\":2.34,\"text\":\"测试语音\"}]}";
    int status=200;
    final AtomicReference<String> request=new AtomicReference<>();
    final AtomicReference<String> authorization=new AtomicReference<>();
    final AtomicReference<String> contentType=new AtomicReference<>();
    @BeforeEach void open() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/transcriptions",exchange->{
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);
            try(var output=exchange.getResponseBody()) {output.write(bytes);}
        });
        server.start();
    }
    @AfterEach void close() {server.stop(0);}
    String url() {return "http://127.0.0.1:"+server.getAddress().getPort()+"/transcriptions";}
    HttpTimedAsrProvider provider() {return new HttpTimedAsrProvider(new ObjectMapper(),url(),"fixture-key","whisper-1",false);}
    Path audio() throws Exception {return Files.writeString(root.resolve("audio.wav"),"fixture-audio-bytes");}

    @Test void sendsRealMultipartAndConvertsActualSecondsToIntegerMilliseconds() throws Exception {
        var cues=provider().transcribe(audio());
        assertEquals(1,cues.size()); assertEquals(120,cues.getFirst().startMs());
        assertEquals(2340,cues.getFirst().endMs()); assertEquals("测试语音",cues.getFirst().text());
        assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
        assertEquals("Bearer fixture-key",authorization.get());
        String body=request.get();
        for(String field:new String[]{"name=\"model\"","whisper-1","name=\"file\"; filename=\"audio.wav\"","fixture-audio-bytes","name=\"response_format\"","verbose_json","name=\"timestamp_granularities[]\"","segment"})
            assertTrue(body.contains(field),field+" missing from multipart request");
    }
    @Test void refusesMissingSegmentsPlainTextAndInvalidTimestamps() throws Exception {
        Path wav=audio();
        for(String bad:new String[]{"plain words","{\"text\":\"plain only\"}","{\"segments\":null}",
                "{\"segments\":[{\"start\":2,\"end\":1,\"text\":\"x\"}]}",
                "{\"segments\":[{\"start\":-1,\"end\":1,\"text\":\"x\"}]}",
                "{\"segments\":[{\"start\":-0.0001,\"end\":1,\"text\":\"x\"}]}",
                "{\"segments\":[{\"start\":\"0\",\"end\":1,\"text\":\"x\"}]}",
                "{\"segments\":[{\"start\":0,\"end\":1,\"text\":null}]}",
                "{\"segments\":[{\"start\":0,\"end\":1,\"text\":\" \"}]}",
                "{\"segments\":[{\"start\":2,\"end\":3,\"text\":\"a\"},{\"start\":1,\"end\":2,\"text\":\"b\"}]}"}) {
            response=bad;
            assertThrows(IllegalStateException.class,()->provider().transcribe(wav),bad);
        }
    }
    @Test void emptySegmentsRepresentSilenceButServiceErrorsAreNeverSuccessful() throws Exception {
        Path wav=audio();response="{\"segments\":[]}";
        assertTrue(provider().transcribe(wav).isEmpty());
        status=503;response="provider-private-secret";
        var failure=assertThrows(IllegalStateException.class,()->provider().transcribe(wav));
        assertFalse(failure.getMessage().contains("private-secret"));
    }
    @Test void disabledAndRemoteUnauthenticatedConfigurationsCannotSendRequests() throws Exception {
        var disabled=new HttpTimedAsrProvider(new ObjectMapper(),"","","",false);
        assertThrows(IllegalStateException.class,disabled::requireConfigured);
        assertThrows(IllegalStateException.class,()->new HttpTimedAsrProvider(new ObjectMapper(),"https://example.com/transcriptions","","whisper-1",true).requireConfigured());
        assertThrows(IllegalStateException.class,()->new HttpTimedAsrProvider(new ObjectMapper(),url(),"","whisper-1",false).requireConfigured());
        assertThrows(IllegalStateException.class,()->new HttpTimedAsrProvider(new ObjectMapper(),"http://example.com/transcriptions","secret","whisper-1",false).requireConfigured());
        assertNull(request.get());
        var local=new HttpTimedAsrProvider(new ObjectMapper(),url(),"","whisper-1",true);
        assertEquals(120,local.transcribe(audio()).getFirst().startMs());assertNull(authorization.get());
    }
    @Test void boundsAudioAndResponseAndDoesNotFollowRedirects() throws Exception {
        Path wav=audio();
        try(var output=Files.newByteChannel(wav,StandardOpenOption.WRITE)){output.position(3L*1024*1024);output.write(java.nio.ByteBuffer.wrap(new byte[]{1}));}
        assertThrows(IllegalStateException.class,()->provider().transcribe(wav));assertNull(request.get());
        Files.writeString(wav,"small");response="x".repeat(2*1024*1024+1);
        assertThrows(IllegalStateException.class,()->provider().transcribe(wav));
        status=302;response="redirect";
        assertThrows(IllegalStateException.class,()->provider().transcribe(wav));
    }
}
