package com.example.server.editor;
import com.fasterxml.jackson.databind.*;
import okhttp3.*;
import okio.ByteString;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.example.server.editor.EditorModels.TimedCue;
/** Paraformer duplex protocol. Audio remains WAV PCM; only server-final sentences become cues. */
@Component
@ConditionalOnProperty(name="editor.timed-asr.provider",havingValue="bailian")
public class BailianTimedAsrProvider implements TimedAsrProvider {
    private static final int MAX_AUDIO=3*1024*1024, MAX_RESULT=2*1024*1024;
    private static final OkHttpClient CLIENT=new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ZERO).writeTimeout(Duration.ofSeconds(15)).followRedirects(false)
        .followSslRedirects(false).retryOnConnectionFailure(false).build();
    private final ObjectMapper json;
    private final String url,key,model;
    private final WebSocket.Factory factory;
    private final long timeoutNanos;
    @Autowired
    public BailianTimedAsrProvider(ObjectMapper json,
        @Value("${editor.timed-asr.bailian.url:wss://dashscope.aliyuncs.com/api-ws/v1/inference}") String url,
        @Value("${editor.timed-asr.bailian.api-key:}") String key,
        @Value("${editor.timed-asr.bailian.model:paraformer-realtime-v2}") String model) {
        this(json,url,key,model,CLIENT,Duration.ofSeconds(150));
    }
    BailianTimedAsrProvider(ObjectMapper json,String url,String key,String model,WebSocket.Factory factory,Duration timeout) {
        this.json=json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);this.url=clean(url);this.key=clean(key);this.model=clean(model);
        this.factory=factory;this.timeoutNanos=Math.min(Duration.ofSeconds(150).toNanos(),timeout.toNanos());
    }
    @Override public void requireConfigured() {
        try {
            URI endpoint=URI.create(url);
            if(!"wss".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getRawUserInfo()!=null
                || endpoint.getRawQuery()!=null || endpoint.getRawFragment()!=null || key.isBlank() || key.length()>8192
                || key.contains("\r") || key.contains("\n") || model.isBlank() || model.length()>128 || model.contains("\r") || model.contains("\n"))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new IllegalStateException("百炼时间轴转写未配置，可导入字幕");}
    }
    @Override public List<TimedCue> transcribe(Path file) {
        requireConfigured();WebSocket socket=null;
        try {
            if(file==null || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>MAX_AUDIO)throw invalid();
            byte[] wav;try(InputStream input=Files.newInputStream(file)){wav=input.readNBytes(MAX_AUDIO+1);}
            long duration=validateWav(wav);
            Session session=new Session(duration);
            socket=factory.newWebSocket(new Request.Builder().url(url).header("Authorization","Bearer "+key).build(),session);
            session.await(false);
            for(int offset=0;offset<wav.length;offset+=3200){
                session.check();int length=Math.min(3200,wav.length-offset);
                if(!socket.send(ByteString.of(wav,offset,length)))throw invalid();
                session.pause(TimeUnit.MILLISECONDS.toNanos(100L*length/3200));
            }
            synchronized(session){session.check();session.finishing=true;}
            if(!socket.send(command("finish-task",session.id,Map.of("input",Map.of()))))throw invalid();
            session.await(true);
            synchronized(session){return List.copyOf(session.cues);}
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("时间轴转写已中断");}
        catch(IOException | RuntimeException failure){throw new IllegalStateException("百炼时间轴转写失败，请重试或导入字幕");}
        finally {if(socket!=null){socket.close(1000,"complete");socket.cancel();}}
    }
    private String command(String action,String id,Map<String,Object> payload) throws IOException {
        return json.writeValueAsString(Map.of("header",Map.of("action",action,"task_id",id,"streaming","duplex"),"payload",payload));
    }
    private final class Session extends WebSocketListener {
        final String id=UUID.randomUUID().toString();final long deadline=System.nanoTime()+timeoutNanos,duration;
        final List<TimedCue> cues=new ArrayList<>();boolean started,finishing,finished,failed;long resultBytes,textBytes,previousStart=-1;
        Session(long duration){this.duration=duration;}
        @Override public void onOpen(WebSocket ws,Response response){
            try{if(!ws.send(command("run-task",id,Map.of("task_group","audio","task","asr","function","recognition","model",model,"input",Map.of(),
                "parameters",Map.of("format","wav","sample_rate",16000,"disfluency_removal_enabled",false)))))fail();}
            catch(Exception invalid){fail();}
        }
        @Override public synchronized void onMessage(WebSocket ws,String message){
            if(failed||finished)return;
            try{
                if(message.length()>MAX_RESULT || (resultBytes+=message.getBytes(StandardCharsets.UTF_8).length)>MAX_RESULT)throw invalid();
                JsonNode data=json.readTree(message),header=data.path("header");
                if(!id.equals(header.path("task_id").asText()))throw invalid();
                switch(header.path("event").asText()){
                    case "task-started" -> {if(started||finishing)throw invalid();started=true;}
                    case "task-finished" -> {if(!started||!finishing)throw invalid();finished=true;}
                    case "result-generated" -> {
                        if(!started)throw invalid();JsonNode sentence=data.path("payload").path("output").path("sentence");
                        if(!sentence.isObject())throw invalid();
                        if(sentence.path("heartbeat").asBoolean(false))break;
                        if(!sentence.path("sentence_end").isBoolean())throw invalid();
                        if(!sentence.path("sentence_end").booleanValue())break;
                        JsonNode text=sentence.get("text");if(text==null||!text.isTextual())throw invalid();
                        String words=text.textValue().trim();if(words.isEmpty())break;
                        JsonNode start=sentence.path("begin_time"),end=sentence.path("end_time");
                        if(!start.isIntegralNumber()||!end.isIntegralNumber()||!start.canConvertToLong()||!end.canConvertToLong())throw invalid();
                        long a=start.longValue(),b=end.longValue();int bytes=words.getBytes(StandardCharsets.UTF_8).length;
                        if(a<0||b<=a||b>duration||a<previousStart||bytes>32768||(textBytes+=bytes)>1024*1024||cues.size()>=10000)throw invalid();
                        cues.add(new TimedCue("segment-"+(cues.size()+1),a,b,words));previousStart=a;
                    }
                    default -> throw invalid();
                }
            }catch(Exception error){failed=true;}
            notifyAll();
        }
        @Override public void onFailure(WebSocket ws,Throwable error,Response response){fail();}
        @Override public synchronized void onClosing(WebSocket ws,int code,String reason){if(!finished)fail();}
        @Override public synchronized void onClosed(WebSocket ws,int code,String reason){if(!finished)fail();}
        synchronized void fail(){failed=true;notifyAll();}
        synchronized void check() throws InterruptedException {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            if(failed||System.nanoTime()>=deadline)throw invalid();
        }
        synchronized void await(boolean end) throws InterruptedException {
            while(!(end?finished:started)){check();TimeUnit.NANOSECONDS.timedWait(this,Math.max(1,deadline-System.nanoTime()));}check();
        }
        synchronized void pause(long nanos) throws InterruptedException {
            long until=System.nanoTime()+nanos;
            while(System.nanoTime()<until){check();TimeUnit.NANOSECONDS.timedWait(this,Math.max(1,Math.min(until,deadline)-System.nanoTime()));}check();
        }
    }
    /** Validates RIFF chunks (including metadata/padding); sends original WAV bytes unchanged. */
    private long validateWav(byte[] wav) {
        if(wav.length<44||wav.length>MAX_AUDIO)throw invalid();
        ByteBuffer b=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if(b.getInt(0)!=0x46464952||b.getInt(8)!=0x45564157||Integer.toUnsignedLong(b.getInt(4))+8!=wav.length)throw invalid();
        boolean format=false,data=false;long dataLength=0;
        for(long offset=12;offset<wav.length;){
            if(offset+8>wav.length)throw invalid();int pos=(int)offset,id=b.getInt(pos);long size=Integer.toUnsignedLong(b.getInt(pos+4)),next=offset+8+size+(size&1);
            if(next>wav.length)throw invalid();
            if(id==0x20746d66){
                if(format||size<16||b.getShort(pos+8)!=1||b.getShort(pos+10)!=1||b.getInt(pos+12)!=16000||b.getInt(pos+16)!=32000||b.getShort(pos+20)!=2||b.getShort(pos+22)!=16)throw invalid();
                format=true;
            }else if(id==0x61746164){if(data||!format||size==0||(size&1)!=0)throw invalid();data=true;dataLength=size;}
            offset=next;
        }
        if(!format||!data||dataLength>60L*32000)throw invalid();return (dataLength*1000+31999)/32000;
    }
    private static IllegalStateException invalid(){return new IllegalStateException("无效的时间轴转写数据");}
    private static String clean(String value){return value==null?"":value.trim();}
}
