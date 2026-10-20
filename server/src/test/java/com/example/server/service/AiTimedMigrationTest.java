package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.*;
import com.example.server.repository.AgentCheckpointRepository;
import com.example.server.service.mode.ModeRegistry;
import com.example.server.utils.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import org.springframework.data.redis.core.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiTimedMigrationTest {
    @Test void migrationClearsTerminalCriticAndAllGoalResultsWhilePreservingRevisionAndFeedback() throws Exception {
        var f=new Fixture();
        f.checkpoints.saveContext(7L,f.coarse);
        for(String goal:List.of("goal A","goal B")) {
            var old=f.state(goal,"old coarse result");f.checkpoints.saveCriticState(7L,old);f.checkpoints.saveResult(7L,old);
        }
        f.checkpoints.stageRevision(7L,"manual goal",AnalysisMode.REVIEW,new AgentState.AgentPlan("manual goal",List.of("user task")));
        f.cache.put("agent:feedback:7",new HashMap<>(Map.of("sentinel","feedback")));
        // No Redis goal index exists: cache invalidation must not rely on that expiring set.
        f.ai.asyncAnalyze(7L,"goal A",AnalysisMode.GENERAL);
        assertEquals("fresh exact result",f.checkpoints.loadResult(7L,"goal A").result().title());
        assertNull(f.checkpoints.loadResult(7L,"goal B"));assertNull(f.checkpoints.loadCriticState(7L,"goal B"));
        assertTrue(f.checkpoints.isRevisionPending(7L,"manual goal",AnalysisMode.REVIEW));
        assertEquals("feedback",f.cache.get("agent:feedback:7").get("sentinel"));
        assertEquals(f.exact.segments(),f.checkpoints.loadContext(7L).segments());
        f.ai.asyncAnalyze(7L,"goal B",AnalysisMode.GENERAL);
        assertEquals("fresh exact result",f.checkpoints.loadResult(7L,"goal B").result().title());
        verify(f.llm,times(2)).execute(any(),any(),any(),anyString());
    }
    @Test void failedCacheInvalidationKeepsOldContextAndDoesNotAnalyzeUntilRetry() throws Exception {
        var f=new Fixture();f.checkpoints.saveContext(7L,f.coarse);
        when(f.redis.scan(any(ScanOptions.class))).thenThrow(new IllegalStateException("cache unavailable"));
        assertThrows(IllegalStateException.class,()->f.ai.asyncAnalyze(7L,"goal A",AnalysisMode.GENERAL));
        assertEquals(f.coarse.segments(),f.checkpoints.loadContext(7L).segments());
        verify(f.llm,never()).execute(any(),any(),any(),anyString());
    }
    @Test void migrationRebuildsPersistedChunksAndActualAnalysisUsesExactRawEvidence() throws Exception {
        var f=new Fixture();f.checkpoints.saveContext(7L,f.coarse);
        f.checkpoints.saveChunks(7L,List.of(new VideoChunk(0,300000,"old summary",List.of(),f.coarse.segments(),List.of())));
        f.ai.asyncAnalyze(7L,"goal A",AnalysisMode.GENERAL);
        var used=org.mockito.ArgumentCaptor.forClass(VideoContext.class);
        verify(f.llm).execute(used.capture(),any(),any(),anyString());
        assertEquals(f.exact.segments(),used.getValue().segments());
        assertEquals(f.exact.segments(),f.checkpoints.loadChunks(7L).stream().flatMap(c->c.rawSegments().stream()).toList());
        verify(f.vectors).deleteMedia(7L);verify(f.vectors).upsert(eq(7L),anyList());
    }
    @Test void failedExactVectorReindexUsesLocalEvidenceScoresInsteadOfOldRemoteIndex() throws Exception {
        var f=new Fixture();f.checkpoints.saveContext(7L,f.coarse);
        when(f.embeddings.embed(anyString())).thenReturn(List.of(1.0));
        doThrow(new IllegalStateException("index unavailable")).when(f.vectors).upsert(eq(7L),anyList());
        assertDoesNotThrow(()->f.ai.asyncAnalyze(7L,"goal A",AnalysisMode.GENERAL));
        assertEquals("fresh exact result",f.checkpoints.loadResult(7L,"goal A").result().title());
        assertEquals(f.exact.segments().size(),f.longs.searchEvidence(7L,f.exact).size());
        assertTrue(f.longs.searchEvidence(7L,f.exact).stream().noneMatch(hit->hit.transcript().contains("old coarse")));
        verify(f.vectors,never()).search(anyLong(),anyList(),anyInt());
    }
    @Test void cachedChunksWithoutEmbeddingsNeverUseOldRemoteScores() throws Exception {
        var f=new Fixture();
        f.checkpoints.saveChunks(7L,List.of(new VideoChunk(0,300000,"exact",List.of(),f.exact.segments(),List.of())));
        when(f.embeddings.embed(anyString())).thenReturn(List.of(1.0));
        assertEquals(2,f.longs.searchEvidence(7L,f.exact).size());
        verify(f.vectors,never()).search(anyLong(),anyList(),anyInt());
    }
    static class Fixture {
        final Map<String,Map<Object,Object>> cache=new HashMap<>();
        final StringRedisTemplate redis=mock(StringRedisTemplate.class);
        final DeepSeekUtils llm=mock(DeepSeekUtils.class);
        final QdrantVectorStore vectors=mock(QdrantVectorStore.class);
        final EmbeddingUtils embeddings=mock(EmbeddingUtils.class);
        final AgentCheckpointService checkpoints;
        final LongVideoContextService longs;
        final AiService ai;
        final VideoContext coarse=new VideoContext("source","goal A",List.of(new VideoContext.VideoSegment(0,360000,"old coarse text",List.of(),List.of())));
        final List<TranscriptSegment> cues=List.of(new TranscriptSegment(120,2340,"first"),new TranscriptSegment(310000,311000,"second"));
        final VideoContext exact=new VideoContext("source","goal A",List.of(new VideoContext.VideoSegment(120,2340,"first",List.of("slide"),List.of("frame")),new VideoContext.VideoSegment(310000,311000,"second",List.of(),List.of())));
        AgentState state(String goal,String title) {return new AgentState(goal,new AgentState.AgentPlan(goal,List.of("task")),new AnalysisResult(title,List.of("first"),List.of(new AnalysisResult.Evidence(120,"ASR","first","first")),List.of(),List.of()),new AgentState.CriticResult(true,List.of(),List.of(),List.of(),List.of()),1);}
        Fixture() throws Exception {
            HashOperations<String,Object,Object> hashes=mock(HashOperations.class);when(redis.opsForHash()).thenReturn(hashes);
            when(hashes.get(anyString(),any())).thenAnswer(c->cache.getOrDefault(c.getArgument(0),Map.of()).get(c.getArgument(1)));
            doAnswer(c->{cache.computeIfAbsent(c.getArgument(0),k->new HashMap<>()).put(c.getArgument(1),c.getArgument(2));return null;}).when(hashes).put(anyString(),any(),any());
            when(hashes.delete(anyString(),any(Object[].class))).thenAnswer(c->{var fields=cache.get(c.getArgument(0));if(fields!=null)for(int i=1;i<c.getArguments().length;i++)fields.remove(c.getArgument(i));return 1L;});
            when(redis.delete(anyString())).thenAnswer(c->cache.remove(c.getArgument(0))!=null);
            when(redis.delete(anyCollection())).thenAnswer(c->{long n=0;for(Object key:c.<Collection<?>>getArgument(0))if(cache.remove(key)!=null)n++;return n;});
            when(redis.scan(any(ScanOptions.class))).thenAnswer(c->{var cursor=mock(Cursor.class);var keys=cache.keySet().stream().filter(k->k.startsWith("agent:checkpoint:7:goal:")).toList().iterator();when(cursor.hasNext()).thenAnswer(x->keys.hasNext());when(cursor.next()).thenAnswer(x->keys.next());return cursor;});
            when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
            var mapper=new MemoryMapper();var json=new ObjectMapper();
            checkpoints=new AgentCheckpointService(redis,json,new AgentCheckpointRepository(redis,json,mapper));
            var telemetry=mock(AgentTelemetry.class);when(telemetry.currentUsage()).thenReturn(new AgentTelemetry.BudgetUsage(0,0));
            when(llm.summarizeChunk(anyList())).thenThrow(new IllegalStateException("local fallback"));
            var retrieval=new VideoEvidenceRetrievalService(llm,embeddings,vectors,telemetry);
            longs=new LongVideoContextService(telemetry,checkpoints,new VideoChunkingService(llm,embeddings,telemetry),retrieval);
            var verifier=mock(EvidenceVerificationService.class);when(verifier.supported(any(),any())).thenReturn(true);when(verifier.supportsClaim(any(),anyString(),any())).thenReturn(true);
            when(llm.plan(any(),anyString())).thenAnswer(c->new AgentState.AgentPlan(c.<VideoContext>getArgument(0).userGoal(),List.of("task")));
            when(llm.execute(any(),any(),any(),anyString())).thenReturn(state("goal A","fresh exact result").result());
            when(llm.critique(any(),any(),any(),anyString())).thenReturn(state("goal A","fresh").critique());
            var events=mock(TaskEventService.class);var loop=new AgentLoopService(llm,longs,checkpoints,telemetry,verifier,events,1,120000,50000,0);
            var mediaMapper=mock(MediaFileMapper.class);var media=new MediaFile();media.setId(7L);media.setUserId(3L);media.setFilePath("source");when(mediaMapper.selectById(7L)).thenReturn(media);
            var unified=mock(UnifiedTranscriptService.class);when(unified.supports(7L,3L)).thenReturn(true);when(unified.await(7L,3L)).thenReturn(cues);when(unified.completed(7L,3L)).thenReturn(cues);
            var contexts=mock(VideoContextService.class);when(contexts.build(eq("source"),anyString(),any(),eq(cues))).thenAnswer(c->new VideoContext("source",c.getArgument(1),exact.segments()));
            var locks=mock(RedissonClient.class);var lock=mock(RLock.class);when(locks.getLock(anyString())).thenReturn(lock);when(lock.tryLock(anyLong(),eq(TimeUnit.SECONDS))).thenReturn(true);
            ai=new AiService(mediaMapper,contexts,longs,loop,checkpoints,telemetry,mock(MediaService.class),events,locks,redis,mock(ModeRegistry.class),llm,json,unified);
        }
    }
    static class MemoryMapper implements AgentCheckpointMapper {
        final Map<String,String> payloads=new HashMap<>(),stages=new HashMap<>();
        String key(Long id,String name){return id+":"+name;}
        public String findPayload(Long id,String name){return payloads.get(key(id,name));}
        public String findStage(Long id,String name){return stages.get(key(id,name));}
        public void upsert(Long id,String name,String stage,String payload){payloads.put(key(id,name),payload);stages.put(key(id,name),stage);}
        public void deleteByPrefix(Long id,String prefix){payloads.keySet().removeIf(k->k.startsWith(key(id,prefix)));stages.keySet().removeIf(k->k.startsWith(key(id,prefix)));}
        public void delete(Long id,String name){payloads.remove(key(id,name));stages.remove(key(id,name));}
        public void deleteByMediaId(Long id){deleteByPrefix(id,"");}
    }
}
