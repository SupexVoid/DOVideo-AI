package com.example.server.editor;

import com.example.server.editor.EditorModels.TimedCue;
import com.fasterxml.jackson.databind.*;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.io.*;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Explicit Whisper-compatible verbose_json adapter; no generic/plain-text ASR fallback.
 * Official protocol: https://developers.openai.com/api/docs/guides/speech-to-text
 * Timestamp granularities on OpenAI require whisper-1, not gpt-4o transcribe models.
 */
@Component
@ConditionalOnProperty(name="editor.timed-asr.provider",havingValue="whisper",matchIfMissing=true)
public class HttpTimedAsrProvider implements TimedAsrProvider {
    private static final int MAX_AUDIO_BYTES=3*1024*1024,MAX_RESULT_BYTES=2*1024*1024;
    private final ObjectMapper json;
    private final String url,key,model;
    private final boolean allowLocalUnauthenticated;
    private final OkHttpClient client=new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(15))
        .callTimeout(Duration.ofSeconds(120)).readTimeout(Duration.ofSeconds(120))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();

    public HttpTimedAsrProvider(ObjectMapper json,@Value("${editor.timed-asr.url:}") String url,
            @Value("${editor.timed-asr.api-key:}") String key,@Value("${editor.timed-asr.model:}") String model,
            @Value("${editor.timed-asr.allow-local-unauthenticated:false}") boolean allowLocalUnauthenticated) {
        this.json=json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.url=clean(url);this.key=clean(key);this.model=clean(model);this.allowLocalUnauthenticated=allowLocalUnauthenticated;
    }
    @Override public void requireConfigured() {
        try {
            URI endpoint=URI.create(url);
            String host=endpoint.getHost();
            boolean local=Set.of("localhost","127.0.0.1","::1","[::1]").contains(host==null?"":host.toLowerCase(Locale.ROOT));
            if(url.isBlank() || model.isBlank() || model.length()>128 || model.contains("\r") || model.contains("\n")
                    || host==null || endpoint.getRawUserInfo()!=null || endpoint.getRawQuery()!=null || endpoint.getRawFragment()!=null
                    || !("https".equalsIgnoreCase(endpoint.getScheme()) || (local && "http".equalsIgnoreCase(endpoint.getScheme())))
                    || (key.isBlank() && !(local && allowLocalUnauthenticated)) || key.length()>8192 || key.contains("\r") || key.contains("\n")
                    || ("api.openai.com".equalsIgnoreCase(host) && !"whisper-1".equals(model)))
                throw new IllegalArgumentException();
        } catch(RuntimeException invalid) {throw new IllegalStateException("时间轴转写未配置，可导入字幕");}
    }
    @Override public List<TimedCue> transcribe(Path audioFile) {
        requireConfigured();
        try {
            if(audioFile==null || !Files.isRegularFile(audioFile,LinkOption.NOFOLLOW_LINKS) || Files.size(audioFile)>MAX_AUDIO_BYTES)
                throw new IllegalStateException("时间轴音频分片无效或过大");
            byte[] audio;
            try(InputStream input=Files.newInputStream(audioFile)){audio=input.readNBytes(MAX_AUDIO_BYTES+1);}
            if(audio.length==0 || audio.length>MAX_AUDIO_BYTES)throw new IllegalStateException("时间轴音频分片无效或过大");
            RequestBody body=new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model",model).addFormDataPart("response_format","verbose_json")
                .addFormDataPart("timestamp_granularities[]","segment")
                .addFormDataPart("file","audio.wav",RequestBody.create(audio,MediaType.get("audio/wav"))).build();
            Request.Builder request=new Request.Builder().url(url).post(body);
            if(!key.isBlank())request.header("Authorization","Bearer "+key);
            try(Response response=client.newCall(request.build()).execute()) {
                if(!response.isSuccessful() || response.body()==null)throw new IllegalStateException("时间轴转写服务调用失败");
                if(response.body().contentLength()>MAX_RESULT_BYTES)throw new IllegalStateException("时间轴转写响应过大");
                byte[] bytes=response.body().byteStream().readNBytes(MAX_RESULT_BYTES+1);
                if(bytes.length>MAX_RESULT_BYTES)throw new IllegalStateException("时间轴转写响应过大");
                return parse(bytes);
            }
        } catch(IOException | IllegalArgumentException error) {throw new IllegalStateException("时间轴转写响应无效或服务暂不可用");}
    }
    private List<TimedCue> parse(byte[] bytes) throws IOException {
        JsonNode data=json.readTree(bytes),segments=data==null?null:data.get("segments");
        if(segments==null || !segments.isArray() || segments.size()>10000)throw new IllegalStateException("时间轴转写响应缺少有效 segments");
        List<TimedCue> result=new ArrayList<>();long previousStart=-1,totalBytes=0;
        for(JsonNode segment:segments) {
            JsonNode start=segment.get("start"),end=segment.get("end"),text=segment.get("text");
            if(start==null || end==null || !start.isNumber() || !end.isNumber() || text==null || !text.isTextual())throw invalidTime();
            long startMs=milliseconds(start),endMs=milliseconds(end);
            String words=text.textValue().trim();int length=words.getBytes(StandardCharsets.UTF_8).length;
            if(startMs<0 || endMs<=startMs || startMs<previousStart || words.isBlank() || length>32768 || (totalBytes+=length)>1024*1024)throw invalidTime();
            result.add(new TimedCue("segment-"+(result.size()+1),startMs,endMs,words));previousStart=startMs;
        }
        return List.copyOf(result);
    }
    private long milliseconds(JsonNode number) {
        try{
            var seconds=number.decimalValue();
            if(seconds.signum()<0)throw invalidTime();
            return seconds.movePointRight(3).setScale(0,RoundingMode.HALF_UP).longValueExact();
        }
        catch(RuntimeException invalid){throw invalidTime();}
    }
    private IllegalStateException invalidTime(){return new IllegalStateException("时间轴转写服务返回无效时间戳或文本");}
    private static String clean(String text){return text==null?"":text.trim();}
}
