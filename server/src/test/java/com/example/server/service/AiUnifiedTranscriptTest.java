package com.example.server.service;
import com.example.server.dto.*;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.mode.ModeRegistry;
import com.example.server.utils.DeepSeekUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiUnifiedTranscriptTest {
    final MediaFileMapper mapper=mock(MediaFileMapper.class);
    final AgentCheckpointService checkpoints=mock(AgentCheckpointService.class);
    final UnifiedTranscriptService unified=mock(UnifiedTranscriptService.class);
    final VideoContextService contexts=mock(VideoContextService.class);
    final AgentLoopService loop=mock(AgentLoopService.class);
    final RedissonClient locks=mock(RedissonClient.class);
    final List<TranscriptSegment> cues=List.of(new TranscriptSegment(120,2340,"first"),new TranscriptSegment(3100,4300,"second"));
    final VideoContext exact=new VideoContext("source","goal",List.of(new VideoContext.VideoSegment(120,2340,"first",List.of(),List.of()),new VideoContext.VideoSegment(3100,4300,"second",List.of(),List.of())));
    final VideoContext coarse=new VideoContext("source","goal",List.of(new VideoContext.VideoSegment(0,60000,"first\nsecond",List.of(),List.of())));
    AiService service() {
        MediaFile file=new MediaFile();file.setId(7L);file.setUserId(3L);file.setFilePath("source");when(mapper.selectById(7L)).thenReturn(file);
        when(unified.supports(7L,3L)).thenReturn(true);when(unified.completed(7L,3L)).thenReturn(cues);when(unified.await(7L,3L)).thenReturn(cues);
        return new AiService(mapper,contexts,mock(LongVideoContextService.class),loop,checkpoints,mock(AgentTelemetry.class),mock(MediaService.class),mock(TaskEventService.class),locks,mock(StringRedisTemplate.class),mock(ModeRegistry.class),mock(DeepSeekUtils.class),new ObjectMapper(),unified);
    }
    @Test void httpCacheEligibilityIsNonblockingAndRequiresExactOwnedCueAlignment() {
        var service=service();when(checkpoints.loadContext(7L)).thenReturn(coarse);
        assertFalse(service.canReuseSavedResult(7L,3L));
        when(checkpoints.loadContext(7L)).thenReturn(exact);assertTrue(service.canReuseSavedResult(7L,3L));
        when(unified.completed(7L,3L)).thenReturn(null);assertFalse(service.canReuseSavedResult(7L,3L));
        verify(unified,never()).await(anyLong(),anyLong());
    }
    @Test void staleFinalResultAndCoarseContextCannotSkipActualCueBuild() throws Exception {
        var service=service();when(checkpoints.loadContext(7L)).thenReturn(coarse);
        AgentState result=new AgentState("goal",null,new AnalysisResult("summary",List.of(),List.of(),List.of(),List.of()),null,1);
        when(checkpoints.loadResult(7L,"goal",AnalysisMode.GENERAL)).thenReturn(result);
        var lock=mock(RLock.class);when(locks.getLock(anyString())).thenReturn(lock);when(lock.tryLock(anyLong(),eq(TimeUnit.SECONDS))).thenReturn(true);
        when(contexts.build("source","goal",null,cues)).thenReturn(exact);when(loop.run(eq(7L),any(),any())).thenReturn(result);
        service.asyncAnalyze(7L,"goal",AnalysisMode.GENERAL);
        verify(contexts).build("source","goal",null,cues);verify(contexts,never()).build(anyString(),anyString(),any());
        verify(loop).run(eq(7L),eq(exact),any());verify(checkpoints).saveContext(7L,exact);
    }
}
