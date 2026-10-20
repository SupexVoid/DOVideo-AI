package com.example.server.service;

import com.example.server.dto.TranscriptSegment;
import com.example.server.editor.EditorModels.TranscriptDocument;
import com.example.server.editor.TimedAsrProvider;
import com.example.server.editor.TimedTranscriptionService;
import com.example.server.editor.ClipRangePlanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.List;

/** Joins the durable editor job from legacy/analysis workers, never from its own ASR executor. */
@Service
public class UnifiedTranscriptService {
    private final TimedTranscriptionService timed;
    private final TimedAsrProvider provider;
    private final long timeoutMs,pollMs;
    public UnifiedTranscriptService(TimedTranscriptionService timed,TimedAsrProvider provider,
            @Value("${editor.unified-await-timeout-ms:7200000}") long timeoutMs,
            @Value("${editor.unified-poll-ms:250}") long pollMs) {
        this.timed=timed;this.provider=provider;this.timeoutMs=Math.max(1,timeoutMs);this.pollMs=Math.max(1,pollMs);
    }
    public boolean supports(Long mediaId,Long userId) {
        // completed() performs ownership validation even when no document exists.
        if(timed.completed(mediaId,userId)!=null)return true;
        try {provider.requireConfigured();return true;}catch(IllegalStateException unavailable){return false;}
    }
    public List<TranscriptSegment> completed(Long mediaId,Long userId) {
        TranscriptDocument doc=timed.completed(mediaId,userId);return doc==null?null:segments(doc);
    }
    public List<TranscriptSegment> await(Long mediaId,Long userId) {
        if(Thread.currentThread().getName().startsWith("editor-timed-asr-"))throw new IllegalStateException("转写工作线程不能等待自身任务");
        if(Thread.currentThread().isInterrupted())throw new IllegalStateException("等待自动转写被中断");
        // Calls the injected Spring proxy; no transaction spans provider work or polling.
        TranscriptDocument doc=timed.submit(mediaId,userId);Long jobId=doc.id();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while(!"COMPLETED".equals(doc.state())) {
            if(!List.of("QUEUED","PROCESSING").contains(doc.state()))throw new IllegalStateException("自动转写失败，请重试");
            if(System.nanoTime()>=deadline)throw new IllegalStateException("等待自动转写超时");
            try {Thread.sleep(Math.min(pollMs,Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))));}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("等待自动转写被中断",interrupted);}
            doc=timed.status(mediaId,userId);
            if(doc==null||!jobId.equals(doc.id()))throw new IllegalStateException("自动转写任务已改变");
        }
        List<TranscriptSegment> result=segments(doc);timed.syncText(doc,userId);return result;
    }
    private List<TranscriptSegment> segments(TranscriptDocument doc) {
        if(!"ASR_TIMED".equals(doc.source())||!"COMPLETED".equals(doc.state())||doc.cues()==null||doc.cues().isEmpty())throw new IllegalStateException("自动转写尚未完成");
        new ClipRangePlanner().plan(doc.cues(),List.of(),doc.durationMs());
        return doc.cues().stream().map(c->new TranscriptSegment(c.startMs(),c.endMs(),c.text())).toList();
    }
}
