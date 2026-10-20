package com.example.server.editor;
import com.example.server.editor.EditorModels.*;
import com.example.server.editor.SmartSelectionModels.*;
import com.example.server.service.AnalysisDispatchService;
import com.example.server.service.AgentExecutionBudget;
import com.example.server.service.AgentTelemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class SmartSelectionServiceTest {
 EditorService editor=mock(EditorService.class);EditorStore store=mock(EditorStore.class);SmartSelectionMapper jobs=mock(SmartSelectionMapper.class);SmartSelectionModel model=mock(SmartSelectionModel.class);AnalysisDispatchService quota=mock(AnalysisDispatchService.class);
 AgentTelemetry telemetry=mock(AgentTelemetry.class);
 Map<String,SmartSelectionRow> rows=new LinkedHashMap<>();List<Runnable> queue=new ArrayList<>();boolean reject;Tx tx=new Tx();SmartSelectionService service;ObjectMapper json=new ObjectMapper();
 @BeforeEach void setup(){
  when(store.lockOwnedMedia(7L,1L)).thenReturn(7L);
  when(editor.getTranscript(anyLong(),anyLong(),anyLong())).thenAnswer(a->{if(!a.getArgument(0).equals(7L)||!a.getArgument(1).equals(8L)||!a.getArgument(2).equals(1L))throw new SecurityException();return document(2);});
  when(jobs.insert(any())).thenAnswer(a->{SmartSelectionRow r=a.getArgument(0);rows.put(r.getId(),r);return 1;});
  when(jobs.active(anyLong(),anyLong(),anyLong(),anyString())).thenAnswer(a->rows.values().stream().filter(r->r.getGoal().equals(a.getArgument(3))&&Set.of("QUEUED","PROCESSING").contains(r.getState())).findFirst().orElse(null));
  when(jobs.find(anyString(),anyLong())).thenAnswer(a->{var r=rows.get(a.getArgument(0));return r!=null&&r.getUserId().equals(a.getArgument(1))?r:null;});
  when(jobs.latest(anyLong(),anyLong(),anyLong())).thenAnswer(a->rows.isEmpty()?null:rows.values().stream().reduce((x,y)->y).orElse(null));
  when(jobs.start(anyString(),any())).thenAnswer(a->{rows.get(a.getArgument(0)).setState("PROCESSING");return 1;});
  when(jobs.progress(anyString(),anyInt(),any())).thenAnswer(a->{rows.get(a.getArgument(0)).setCompletedBatches(a.getArgument(1));return 1;});
  when(jobs.complete(anyString(),anyString(),anyLong(),any())).thenAnswer(a->{var r=rows.get(a.getArgument(0));r.setState("COMPLETED");r.setResultJson(a.getArgument(1));r.setDurationMs(a.getArgument(2));return 1;});
  when(jobs.fail(anyString(),anyString(),anyString(),any())).thenAnswer(a->{var r=rows.get(a.getArgument(0));r.setState("FAILED");r.setErrorCode(a.getArgument(1));r.setErrorMessage(a.getArgument(2));return 1;});
  when(model.select(anyString(),anyList())).thenAnswer(a->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(AgentExecutionBudget.remainingMillis()<=480000);return "{\"selections\":[{\"cueIds\":[\"c0\"],\"reason\":\"context\"}]}";});
  when(telemetry.start(anyLong(),anyString())).thenReturn("trace-id");
  service=new SmartSelectionService(editor,store,jobs,model,quota,telemetry,json,r->{if(reject)throw new RejectedExecutionException();queue.add(r);},tx,"fixture-model");
 }
 TranscriptDocument document(int n){var cues=new ArrayList<TimedCue>();for(int i=0;i<n;i++)cues.add(new TimedCue("c"+i,i*1000,(i+1)*1000,"text"));return new TranscriptDocument(8L,7L,2,"ASR_TIMED",n*1000,"COMPLETED",null,cues);}
 Job submit(){return service.submit(new Request(7L,8L,"keep dialogue"),1L);}
 @AfterEach void clear(){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
 @Test void persistsImmutableSnapshotReusesActiveThenCompletesWithoutProjectMutation(){var job=submit();assertEquals(job.id(),submit().id());assertEquals(1,queue.size());assertTrue(rows.get(job.id()).getSnapshotJson().contains("revision"));queue.removeFirst().run();var result=service.get(job.id(),1L);assertEquals("COMPLETED",result.state());assertEquals(List.of("c0"),result.keptCueIds());assertEquals(1000,result.durationMs());assertEquals(1,result.completedBatches());verify(quota,times(1)).requireAiQuota(1L);verify(editor,never()).saveProject(any(),any());assertEquals(job.id(),service.latest(7L,8L,1L).id());}
 @Test void ownershipAndGoalValidationPrecedeQueueAndModel(){assertThrows(SecurityException.class,()->service.submit(new Request(9L,8L,"x"),1L));assertThrows(IllegalArgumentException.class,()->service.submit(new Request(7L,8L,"x".repeat(501)),1L));var j=submit();assertThrows(NoSuchElementException.class,()->service.get(j.id(),2L));verifyNoInteractions(model);}
 @Test void queueRejectionAfterCommitUsesNewTransactionAndSafeFailure(){reject=true;TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);var j=submit();assertEquals("QUEUED",j.state());TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,tx.propagation);assertEquals(1,tx.commits);assertEquals("SELECTION_QUEUE_FULL",service.get(j.id(),1L).errorCode());}
 @Test void laterBatchFailureNeverPublishesPartialResultAndKeepsProgress(){when(editor.getTranscript(7L,8L,1L)).thenReturn(document(105));doReturn("{\"selections\":[{\"cueIds\":[\"c0\"],\"reason\":\"ok\"}]}").doThrow(new IllegalStateException("secret-key-provider-url")).when(model).select(anyString(),anyList());var j=submit();queue.removeFirst().run();var failed=service.get(j.id(),1L);assertEquals("FAILED",failed.state());assertEquals(1,failed.completedBatches());assertEquals(2,failed.totalBatches());assertTrue(failed.keptCueIds().isEmpty());assertFalse(failed.errorMessage().contains("secret"));}
 @Test void foreignBatchIdFailsEvenIfInWholeTranscript(){when(editor.getTranscript(7L,8L,1L)).thenReturn(document(105));doReturn("{\"selections\":[{\"cueIds\":[\"c104\"],\"reason\":\"bad\"}]}").when(model).select(anyString(),anyList());var j=submit();queue.removeFirst().run();assertEquals("SELECTION_INVALID_OUTPUT",service.get(j.id(),1L).errorCode());}
 @Test void emptyRecommendationCompletesAndStartupInterruptsUnfinished(){doReturn("{\"selections\":[]}").when(model).select(anyString(),anyList());var j=submit();queue.removeFirst().run();assertEquals("COMPLETED",service.get(j.id(),1L).state());assertEquals(0,service.get(j.id(),1L).durationMs());service.afterSingletonsInstantiated();verify(jobs).interrupt(any());}
 @Test void completionWriteFailureIsRecorded(){doThrow(new IllegalStateException("DB secret")).when(jobs).complete(anyString(),anyString(),anyLong(),any());var j=submit();queue.removeFirst().run();assertEquals("SELECTION_SAVE_FAILED",service.get(j.id(),1L).errorCode());}
 @Test void wrappedModelDeadlineHasExplicitSafeTimeoutCode(){doThrow(new IllegalArgumentException("provider secret",new AgentExecutionBudget.DeadlineExceededException("deadline"))).when(model).select(anyString(),anyList());var j=submit();queue.removeFirst().run();var result=service.get(j.id(),1L);assertEquals("SELECTION_TIMEOUT",result.errorCode());assertFalse(result.errorMessage().contains("provider"));}
 @Test void overlappingSuccessfulBatchesMergeInOrderWithoutDoubleCounting(){when(editor.getTranscript(7L,8L,1L)).thenReturn(document(105));doReturn("{\"selections\":[{\"cueIds\":[\"c99\",\"c98\"],\"reason\":\"first\"}]}","{\"selections\":[{\"cueIds\":[\"c104\",\"c98\"],\"reason\":\"next\"}]}").when(model).select(anyString(),anyList());var j=submit();queue.removeFirst().run();var r=service.get(j.id(),1L);assertEquals("COMPLETED",r.state());assertEquals(List.of("c98","c99","c104"),r.keptCueIds());assertEquals(3000,r.durationMs());assertEquals(2,r.completedBatches());}
 @Test void rollbackDoesNotQueueOrSpendModelCalls(){TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);submit();TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));assertTrue(queue.isEmpty());verifyNoInteractions(model);}
 @Test void deletionDuringFirstBatchStopsFurtherPaidCallsAndCompletion(){when(editor.getTranscript(7L,8L,1L)).thenReturn(document(105));doAnswer(a->{when(editor.getTranscript(7L,8L,1L)).thenThrow(new SecurityException("deleted"));return "{\"selections\":[]}";}).when(model).select(anyString(),anyList());var j=submit();queue.removeFirst().run();assertEquals("FAILED",rows.get(j.id()).getState());verify(model,times(1)).select(anyString(),anyList());verify(jobs,never()).complete(anyString(),anyString(),anyLong(),any());}
 @Test void clearsTelemetryWhenModelFails(){doThrow(new IllegalStateException("provider")).when(model).select(anyString(),anyList());submit();queue.removeFirst().run();verify(telemetry).flush("trace-id");verify(telemetry).clear();}
 @Test void bindsAndClearsTelemetryForPaidTask(){var j=submit();queue.removeFirst().run();verify(telemetry).start(7L,"smart-selection:"+j.id());verify(telemetry).flush("trace-id");verify(telemetry).clear();}
 static class Tx extends AbstractPlatformTransactionManager{int commits,propagation;protected Object doGetTransaction(){return new Object();}protected void doBegin(Object t,TransactionDefinition d){propagation=d.getPropagationBehavior();}protected void doCommit(DefaultTransactionStatus s){commits++;}protected void doRollback(DefaultTransactionStatus s){}}
}
