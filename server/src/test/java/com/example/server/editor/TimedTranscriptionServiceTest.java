package com.example.server.editor;

import com.example.server.common.ErrorCode;
import com.example.server.editor.EditorModels.*;
import com.example.server.editor.EditorStore.*;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.io.OutputStream;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TimedTranscriptionServiceTest {
    @TempDir Path root;
    Fixture f;
    @BeforeEach void setup() throws Exception {f=new Fixture(root);}
    @AfterEach void clearTransaction() {
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    @Test void ownershipPrecedesConfigurationAndEveryStatusRead() {
        doThrow(new IllegalStateException("disabled")).when(f.provider).requireConfigured();
        assertThrows(SecurityException.class,()->f.service.submit(8L,1L));
        assertThrows(SecurityException.class,()->f.service.status(8L,1L));
        assertThrows(SecurityException.class,()->f.service.status(7L,2L));
        BusinessException unavailable=assertThrows(BusinessException.class,()->f.submit());
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,unavailable.errorCode());
        assertEquals("时间轴转写未配置，可导入字幕",unavailable.getMessage());
        assertTrue(f.store.transcripts.isEmpty());assertTrue(f.queue.isEmpty());verifyNoInteractions(f.minio);
    }
    @Test void queuesOneActiveDocumentWithSerializedRevisionAndNoEstimatedDuration() {
        assertNull(f.service.status(7L,1L));
        var doc=f.submit();
        assertEquals("ASR_TIMED",doc.source());assertEquals("QUEUED",doc.state());
        assertEquals(0,doc.durationMs());assertTrue(doc.cues().isEmpty());
        assertEquals(doc.id(),f.submit().id());assertEquals(1,f.queue.size());
        assertEquals(1,f.store.transcripts.size());assertEquals(doc.id(),f.service.status(7L,1L).id());
        verifyNoInteractions(f.minio);
    }
    @Test void repeatedCompletedSubmissionReusesPublishedAsrEvenWhenProviderIsNowDisabled() {
        var queued=f.submit();f.run();
        doThrow(new IllegalStateException("disabled")).when(f.provider).requireConfigured();
        assertEquals(queued.id(),assertDoesNotThrow(f::submit).id());
        assertTrue(f.queue.isEmpty());assertEquals(1,f.store.transcripts.size());
        verify(f.provider,times(2)).transcribe(any());
    }
    @Test void newerFailedOrImportedRevisionCannotHideCompletedAutomaticTranscript() {
        var completed=f.submit();f.run();
        TranscriptRow failed=new TranscriptRow();failed.setMediaId(7L);failed.setUserId(1L);failed.setRevision(2);failed.setSource("ASR_TIMED");failed.setState("FAILED");failed.setCuesJson("[]");f.store.insertTranscript(failed);
        TranscriptRow imported=new TranscriptRow();imported.setMediaId(7L);imported.setUserId(1L);imported.setRevision(3);imported.setSource("SUBTITLE_IMPORT");imported.setState("COMPLETED");imported.setCuesJson("[]");f.store.insertTranscript(imported);
        doThrow(new IllegalStateException("disabled")).when(f.provider).requireConfigured();
        assertEquals(completed.id(),f.submit().id());assertEquals(completed.id(),f.service.completed(7L,1L).id());assertTrue(f.queue.isEmpty());
    }
    @Test void manualSubtitlesDoNotReplaceAutomaticRecognition() {
        TranscriptRow imported=new TranscriptRow();imported.setMediaId(7L);imported.setUserId(1L);imported.setRevision(1);imported.setSource("SUBTITLE_IMPORT");imported.setState("COMPLETED");imported.setCuesJson("[]");f.store.insertTranscript(imported);
        assertNull(f.service.completed(7L,1L));var automatic=f.submit();assertEquals("ASR_TIMED",automatic.source());assertEquals(2,automatic.revision());assertEquals(1,f.queue.size());
    }
    @Test void completionSynchronizesOnlyOwnedOriginalTextAndInvalidatesList() {
        when(f.mediaFiles.update(any(MediaFile.class),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        f.submit();f.run();
        var update=org.mockito.ArgumentCaptor.forClass(MediaFile.class);
        var scope=org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(f.mediaFiles).update(update.capture(),scope.capture());
        assertEquals("fixture words\nfixture words",update.getValue().getTranscriptText());assertNull(update.getValue().getAiSummary());assertNull(update.getValue().getFilePath());assertNull(update.getValue().getFilename());
        var wrapper=(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?>)scope.getValue();
        assertTrue(wrapper.getSqlSegment().contains("user_id"));assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));assertTrue(wrapper.getParamNameValuePairs().containsValue(1L));
        verify(f.media).invalidateUserList(1L);
    }
    @Test void dispatchWaitsForCommitAndRollbackNeverStartsWork() throws Exception {
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        f.submit();assertTrue(f.queue.isEmpty());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertEquals(1,f.queue.size());
        TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.initSynchronization();
        Fixture rollback=new Fixture(root);rollback.submit();
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertTrue(rollback.queue.isEmpty());verifyNoInteractions(rollback.minio);
    }
    @Test void rejectionAfterCommitUsesNewCommittedTransactionAndPersistsFailure() {
        f.reject=true;
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        var doc=f.submit();assertEquals(0,f.transactions.commits);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertEquals(1,f.transactions.commits);assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,f.transactions.propagation);
        assertEquals("FAILED",f.service.status(7L,1L).state());assertEquals("TIMED_ASR_QUEUE_FULL",f.service.status(7L,1L).errorCode());
        assertEquals(doc.id(),f.service.status(7L,1L).id());
    }
    @Test void chunksUseActualBoundariesAndRealDurationThenRemoveOnlyOwnedFiles() throws Exception {
        Path keep=Files.writeString(root.resolve("keep.txt"),"keep");
        var doc=f.submit();Runnable run=f.queue.removeFirst();run.run();
        var done=f.service.status(7L,1L);
        assertEquals("COMPLETED",done.state());assertEquals(65000,done.durationMs());
        assertEquals(List.of("0:60000","60000:5000"),f.chunks);
        assertEquals(List.of(120L,60120L),done.cues().stream().map(TimedCue::startMs).toList());
        assertEquals(List.of(2340L,62340L),done.cues().stream().map(TimedCue::endMs).toList());
        assertEquals(2,done.cues().stream().map(TimedCue::id).distinct().count());
        String immutable=f.store.transcripts.get(doc.id()).getCuesJson();run.run();
        assertEquals(immutable,f.store.transcripts.get(doc.id()).getCuesJson());assertEquals(2,f.chunks.size());
        try(var children=Files.list(root)){assertEquals(List.of(keep),children.toList());}
        assertEquals(doc.id(),f.submit().id());assertEquals(1,f.service.status(7L,1L).revision());
    }
    @Test void rejectsOutOfChunkTimesAndDoesNotPublishPartialResults() throws Exception {
        when(f.tools.probeDurationMs(any())).thenReturn(1000L);
        doReturn(1000L).when(f.tools).probeAudioDurationMs(any());
        f.submit();f.run();
        var failed=f.service.status(7L,1L);
        assertEquals("FAILED",failed.state());assertTrue(failed.cues().isEmpty());
        assertEquals("TIMED_ASR_FAILED",failed.errorCode());
        try(var files=Files.list(root)){assertEquals(0,files.count());}
    }
    @Test void providerFailureAndAllSilenceNeverBecomeCompletedDocuments() throws Exception {
        when(f.provider.transcribe(any())).thenThrow(new IllegalStateException("private-key-do-not-return"));
        f.submit();f.run();assertEquals("TIMED_ASR_FAILED",f.service.status(7L,1L).errorCode());
        doReturn(List.of()).when(f.provider).transcribe(any());
        f.submit();f.run();assertEquals("FAILED",f.service.status(7L,1L).state());
        assertTrue(f.service.status(7L,1L).cues().isEmpty());
    }
    @Test void changedSourceAndExcessiveRealDurationFailBeforeProviderCalls() {
        f.submit();f.sourcePath="http://minio/bucket/changed.mp4";f.run();
        assertEquals("TIMED_ASR_SOURCE_CHANGED",f.service.status(7L,1L).errorCode());verify(f.provider,never()).transcribe(any());
        f.sourcePath="http://minio/bucket/source.mp4";
        when(f.tools.probeDurationMs(any())).thenReturn(EditorService.MAX_DURATION_MS+1);
        f.submit();f.run();assertEquals("FAILED",f.service.status(7L,1L).state());verify(f.provider,never()).transcribe(any());
    }
    @Test void completionWriteFailureBecomesRetryableFailureButAmbiguousCompletedWriteRemainsImmutable() {
        f.jobs.completionFailure=true;f.submit();assertDoesNotThrow(f::run);
        assertEquals("FAILED",f.service.status(7L,1L).state());assertEquals("TIMED_ASR_SAVE_FAILED",f.service.status(7L,1L).errorCode());
        f.jobs.completeBeforeThrow=true;f.submit();assertDoesNotThrow(f::run);
        var completed=f.service.status(7L,1L);assertEquals("COMPLETED",completed.state());assertEquals(2,completed.cues().size());
    }
    @Test void startWriteFailureFailsQueuedDocumentAndAllowsResubmission() {
        var queued=f.submit();f.jobs.startFailure=true;
        assertDoesNotThrow(f::run);
        var failed=f.service.status(7L,1L);
        assertEquals("FAILED",failed.state());assertEquals("TIMED_ASR_FAILED",failed.errorCode());
        assertTrue(failed.cues().isEmpty());assertTrue(f.chunks.isEmpty());verifyNoInteractions(f.minio);
        assertNotEquals(queued.id(),f.submit().id());
    }
    @Test void ambiguousSuccessfulStartWriteFailsProcessingDocumentAndAllowsResubmission() {
        var queued=f.submit();f.jobs.startFailure=true;f.jobs.startBeforeThrow=true;
        assertDoesNotThrow(f::run);
        var failed=f.service.status(7L,1L);
        assertEquals("FAILED",failed.state());assertEquals("TIMED_ASR_FAILED",failed.errorCode());
        assertTrue(failed.cues().isEmpty());assertTrue(f.chunks.isEmpty());verifyNoInteractions(f.minio);
        assertNotEquals(queued.id(),f.submit().id());
    }
    @Test void failedStartOfRedeliveredCompletedJobPreservesPublishedTimeline() {
        f.submit();Runnable run=f.queue.removeFirst();run.run();
        var completed=f.service.status(7L,1L);f.jobs.startFailure=true;
        assertDoesNotThrow(run::run);
        assertEquals(completed,f.service.status(7L,1L));assertEquals(2,f.chunks.size());
    }
    @Test void startupOnlyInterruptsActiveTimedAsrDocumentsAndNeverChangesCompletedOrImportedCues() {
        var complete=f.submit();f.run();String cues=f.store.transcripts.get(complete.id()).getCuesJson();
        var active=f.submit();
        // Seed an unfinished historical refresh; normal submit now reuses completed ASR.
        TranscriptRow pending=new TranscriptRow();pending.setMediaId(7L);pending.setUserId(1L);pending.setRevision(2);pending.setSource("ASR_TIMED");pending.setState("QUEUED");pending.setCuesJson("[]");f.store.insertTranscript(pending);active=f.service.status(7L,1L);
        TranscriptRow imported=new TranscriptRow();imported.setMediaId(7L);imported.setUserId(1L);imported.setRevision(3);
        imported.setState("QUEUED");imported.setSource("SUBTITLE_IMPORT");imported.setCuesJson("[]");f.store.insertTranscript(imported);
        f.service.interruptPending();
        assertEquals("TIMED_ASR_INTERRUPTED",f.store.transcripts.get(active.id()).getErrorCode());
        assertEquals("COMPLETED",f.store.transcripts.get(complete.id()).getState());assertEquals(cues,f.store.transcripts.get(complete.id()).getCuesJson());
        assertEquals("QUEUED",imported.getState());assertEquals("FAILED",f.store.transcripts.get(active.id()).getState());
    }
    static class Fixture {
        final EditorServiceTest.MemoryStore store=new EditorServiceTest.MemoryStore();
        final MemoryJobs jobs=new MemoryJobs(store);
        final MediaService media=mock(MediaService.class);final MinioUtils minio=mock(MinioUtils.class);
        final com.example.server.mapper.MediaFileMapper mediaFiles=mock(com.example.server.mapper.MediaFileMapper.class);
        final FfmpegMediaTools tools=mock(FfmpegMediaTools.class);final TimedAsrProvider provider=mock(TimedAsrProvider.class);
        final List<Runnable> queue=new ArrayList<>();final List<String> chunks=new ArrayList<>();
        final Transactions transactions=new Transactions();final TimedTranscriptionService service;
        boolean reject;String sourcePath="http://minio/bucket/source.mp4";
        Fixture(Path root) throws Exception {
            when(media.requireOwnedMedia(anyLong(),anyLong())).thenAnswer(c->{
                if(c.<Long>getArgument(0)!=7L || c.<Long>getArgument(1)!=1L)throw new SecurityException("foreign");
                MediaFile file=new MediaFile();file.setId(7L);file.setUserId(1L);file.setFilePath(sourcePath);return file;
            });
            doAnswer(c->{c.<OutputStream>getArgument(1).write("managed-source".getBytes());return null;}).when(minio).copyManagedFileTo(anyString(),any());
            when(tools.probeDurationMs(any())).thenReturn(65000L);
            doAnswer(c->{
                long start=c.getArgument(2),length=c.getArgument(3);chunks.add(start+":"+length);
                Files.writeString(c.getArgument(1),Long.toString(length));return null;
            }).when(tools).extractAudioChunk(any(),any(),anyLong(),anyLong());
            when(tools.probeAudioDurationMs(any())).thenAnswer(c->Long.parseLong(Files.readString(c.getArgument(0))));
            when(provider.transcribe(any())).thenReturn(List.of(new TimedCue("segment-1",120,2340,"fixture words")));
            service=new TimedTranscriptionService(store,jobs,media,mediaFiles,minio,tools,provider,new ObjectMapper(),r->{if(reject)throw new RejectedExecutionException();queue.add(r);},transactions,root.toString());
        }
        TranscriptDocument submit(){return service.submit(7L,1L);}
        void run(){queue.removeFirst().run();}
    }
    static class Transactions implements PlatformTransactionManager {
        int commits,propagation;
        public TransactionStatus getTransaction(TransactionDefinition definition){propagation=definition.getPropagationBehavior();return new SimpleTransactionStatus();}
        public void commit(TransactionStatus status){commits++;}
        public void rollback(TransactionStatus status){}
    }
    static class MemoryJobs implements TimedAsrMapper {
        final EditorServiceTest.MemoryStore store;
        boolean completionFailure,completeBeforeThrow,startFailure,startBeforeThrow;
        MemoryJobs(EditorServiceTest.MemoryStore store){this.store=store;}
        public TranscriptRow latest(Long mediaId,Long userId){return store.transcripts.values().stream().filter(r->r.getMediaId().equals(mediaId)&&r.getUserId().equals(userId)&&"ASR_TIMED".equals(r.getSource())).max(Comparator.comparingInt(TranscriptRow::getRevision)).orElse(null);}
        public TranscriptRow completed(Long mediaId,Long userId){return store.transcripts.values().stream().filter(r->r.getMediaId().equals(mediaId)&&r.getUserId().equals(userId)&&"ASR_TIMED".equals(r.getSource())&&"COMPLETED".equals(r.getState())).max(Comparator.comparingInt(TranscriptRow::getRevision)).orElse(null);}
        public TranscriptRow active(Long mediaId,Long userId){return store.transcripts.values().stream().filter(r->r.getMediaId().equals(mediaId)&&r.getUserId().equals(userId)&&"ASR_TIMED".equals(r.getSource())&&List.of("QUEUED","PROCESSING").contains(r.getState())).findFirst().orElse(null);}
        public int start(Long id,Long userId,LocalDateTime now){if(startFailure&&!startBeforeThrow)throw new IllegalStateException("start persistence");var r=store.findTranscript(id,userId);if(r==null||!"ASR_TIMED".equals(r.getSource())||!"QUEUED".equals(r.getState()))return 0;r.setState("PROCESSING");if(startFailure)throw new IllegalStateException("ambiguous start commit");return 1;}
        public int complete(Long id,Long userId,long duration,String cues,LocalDateTime now){var r=store.findTranscript(id,userId);if(r==null||!"ASR_TIMED".equals(r.getSource())||!"PROCESSING".equals(r.getState()))return 0;if(completionFailure&&!completeBeforeThrow)throw new IllegalStateException("persistence");r.setState("COMPLETED");r.setDurationMs(duration);r.setCuesJson(cues);if(completionFailure)throw new IllegalStateException("ambiguous commit");return 1;}
        public int fail(Long id,Long userId,String code,LocalDateTime now){var r=store.findTranscript(id,userId);if(r==null||!"ASR_TIMED".equals(r.getSource())||!List.of("QUEUED","PROCESSING").contains(r.getState()))return 0;r.setState("FAILED");r.setErrorCode(code);return 1;}
        public int interrupt(LocalDateTime now){int n=0;for(var r:store.transcripts.values())if(fail(r.getId(),r.getUserId(),"TIMED_ASR_INTERRUPTED",now)==1)n++;return n;}
    }
}
