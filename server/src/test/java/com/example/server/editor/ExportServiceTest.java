package com.example.server.editor;

import com.example.server.common.ErrorCode;
import com.example.server.editor.EditorModels.*;
import com.example.server.editor.EditorStore.*;
import com.example.server.editor.ExportService.*;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExportServiceTest {
    @TempDir Path root;
    Fixture f;
    @BeforeEach void setup() throws Exception { f=new Fixture(root); }
    @AfterEach void clearTransaction() {
        if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test void canonicalRangesReuseQueuedAndCompletedJobs() {
        ExportJob one=f.submit();
        f.selection=List.of("b","a","a");
        assertEquals(one.id(),f.submit().id());
        assertEquals(1,f.queue.size());
        f.runNext();
        ExportJob done=f.submit();
        assertEquals(one.id(),done.id()); assertEquals("COMPLETED",done.state());
        assertEquals(0,f.queue.size()); assertEquals(1,f.uploads.size());
    }
    @Test void fingerprintSeparatesOwnersAndUsesStableSourceIdentity() {
        ExportJob one=f.submit(); ExportJob other=f.service.submit(new ExportRequest(5L,1,"mp4"),2L);
        assertNotEquals(one.id(),other.id()); assertEquals(2,f.store.rows.size());
        for(var row:f.store.rows.values()) {
            assertEquals("http://minio/bucket/source.mp4",row.getSourceObject());
            assertEquals(64,row.getRequestHash().length());
            assertFalse(row.getSnapshotJson().contains("Signature"));
        }
    }
    @Test void workerUsesSubmissionSnapshotAndCleansOnlyItsOwnDirectory() throws Exception {
        Path keep=Files.writeString(root.resolve("keep.txt"),"keep");
        ExportJob job=f.submit(); f.selection=List.of("c"); f.version=2;
        f.runNext();
        ExportJob done=f.service.get(job.id(),1L);
        assertEquals("COMPLETED",done.state()); assertEquals(1,done.projectVersion());
        assertEquals(List.of("0:2000"),f.uploads);
        assertEquals("signed-result",f.service.download(job.id(),1L));
        try(var files=Files.list(root)) { assertEquals(List.of(keep),files.toList()); }
    }
    @Test void jobReadsAndDownloadsRecheckOwnerAndExistingMedia() {
        ExportJob job=f.submit();
        assertThrows(NoSuchElementException.class,()->f.service.get(job.id(),2L));
        assertThrows(NoSuchElementException.class,()->f.service.download(job.id(),2L));
        f.runNext(); f.deleted=true;
        assertThrows(SecurityException.class,()->f.service.get(job.id(),1L));
        assertThrows(SecurityException.class,()->f.service.latest(5L,1L));
        assertThrows(SecurityException.class,()->f.service.download(job.id(),1L));
    }
    @Test void staleVersionEmptySelectionAndBadPresetCreateNoJob() {
        BusinessException stale=assertThrows(BusinessException.class,()->f.service.submit(new ExportRequest(5L,0,"mp4"),1L));
        assertEquals(ErrorCode.CONFLICT,stale.errorCode());
        f.selection=List.of();
        assertThrows(IllegalArgumentException.class,()->f.submit());
        assertThrows(IllegalArgumentException.class,()->f.service.submit(new ExportRequest(5L,1,"webm"),1L));
        assertTrue(f.store.rows.isEmpty()); assertTrue(f.queue.isEmpty());
    }
    @Test void dispatchWaitsForCommitAndRollbackNeverDispatches() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        f.submit(); assertTrue(f.queue.isEmpty());
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCommit());
        assertEquals(1,f.queue.size());
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        f.selection=List.of("c");f.submit();
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(1));
        assertEquals(1,f.queue.size());
    }
    @Test void executorRejectionIsPersistedAsSafeFailure() {
        f.reject=true;
        ExportJob job=f.submit();
        ExportJob failed=f.service.get(job.id(),1L);
        assertEquals("FAILED",failed.state()); assertEquals("EXPORT_QUEUE_FULL",failed.errorCode());
        assertFalse(failed.errorMessage().contains("secret")); assertEquals(1,failed.attempt());
    }
    @Test void explicitRetriesStopAfterThirdAttemptAndStaleRunnableCannotExecute() {
        ExportJob job=f.submit(); Runnable old=f.queue.removeFirst();
        f.store.failExport(job.id(),1,"EXPORT_FAILED","failure",LocalDateTime.now());
        assertEquals(2,f.submit().attempt());
        old.run(); assertEquals("QUEUED",f.service.get(job.id(),1L).state());
        f.store.failExport(job.id(),2,"EXPORT_FAILED","failure",LocalDateTime.now());
        assertEquals(3,f.submit().attempt());
        f.store.failExport(job.id(),3,"EXPORT_FAILED","failure",LocalDateTime.now());
        BusinessException limit=assertThrows(BusinessException.class,()->f.submit());
        assertEquals(ErrorCode.CONFLICT,limit.errorCode()); assertEquals(2,f.queue.size());
    }
    @Test void workerFailureIsDurableSanitizedAndCleaned() throws Exception {
        doThrow(new IllegalStateException("secret key at C:/private/source?Signature=x"))
            .when(f.exporter).export(any(),anyList(),any());
        ExportJob job=f.submit();f.runNext();
        ExportJob failed=f.service.get(job.id(),1L);
        assertEquals("FAILED",failed.state()); assertEquals("EXPORT_FAILED",failed.errorCode());
        assertFalse(failed.errorMessage().contains("private")); assertFalse(failed.errorMessage().contains("secret"));
        assertThrows(BusinessException.class,()->f.service.download(job.id(),1L));
        assertTrue(f.uploads.isEmpty());
        try(var files=Files.list(root)) { assertEquals(0,files.count()); }
    }
    @Test void sourceMutationFailsBeforeUploading() {
        ExportJob job=f.submit();f.sourcePath="http://minio/bucket/replacement.mp4";f.runNext();
        assertEquals("SOURCE_CHANGED",f.service.get(job.id(),1L).errorCode()); assertTrue(f.uploads.isEmpty());
    }
    @Test void copiedSourceHashMustMatchPersistedIdentity() {
        f.contentHash="00000000000000000000000000000000";
        ExportJob job=f.submit();f.runNext();
        assertEquals("SOURCE_CHANGED",f.service.get(job.id(),1L).errorCode()); assertTrue(f.uploads.isEmpty());
    }
    @Test void startupMarksOnlyUnfinishedJobsInterruptedAndAllowsRetry() {
        ExportJob queued=f.submit(); f.selection=List.of("c"); ExportJob processing=f.submit();
        f.store.startExport(processing.id(),1,LocalDateTime.now());
        f.service.interruptPending();
        assertEquals("EXPORT_INTERRUPTED",f.service.get(queued.id(),1L).errorCode());
        assertEquals("FAILED",f.service.get(processing.id(),1L).state());
        f.selection=List.of("a","b");assertEquals(2,f.submit().attempt());
    }
    @Test void uniqueConstraintWinnerIsReusedWithoutDispatchingAgain() {
        f.store.duplicateNext=true;
        ExportJob winner=f.submit();
        assertEquals("winner",winner.id()); assertEquals(1,f.store.rows.size()); assertEquals(0,f.queue.size());
    }
    @Test void latestReturnsNullForOwnedProjectWithoutJobs() { assertNull(f.service.latest(5L,1L)); }
    @Test void snapshotMetadataSupportsEquivalentPlanDownloadsWithoutRewritingVersion() throws Exception {
        ExportJob job=f.submit();f.runNext();f.version=2;
        ExportJob reused=f.service.submit(new ExportRequest(5L,2,"mp4"),1L);
        assertEquals(job.id(),reused.id());assertEquals(1,reused.projectVersion());
        var payload=new ObjectMapper().findAndRegisterModules().valueToTree(reused);
        assertEquals(7,payload.path("mediaId").asLong());assertEquals(9,payload.path("transcriptId").asLong());
        assertEquals(0,payload.path("ranges").path(0).path("startMs").asLong(-1));
        assertEquals(2000,payload.path("ranges").path(0).path("endMs").asLong(-1));
        assertFalse(payload.has("sourceObject"));
    }
    @Test void newProjectCanRestoreTheExistingEquivalentContentJob() {
        ExportJob job=f.submit();f.runNext();
        ExportJob restored=f.service.latest(6L,1L);
        assertNotNull(restored);assertEquals(job.id(),restored.id());assertEquals(5L,restored.projectId());
        assertEquals(1,f.uploads.size());assertTrue(f.queue.isEmpty());
    }
    @Test void boundedExecutorRejectsTheSixthTaskAndPersistsFailure() throws Exception {
        ThreadPoolTaskExecutor pool=new ExportExecutorConfig().editorExportExecutor();pool.initialize();
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            pool.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(started.await(5,TimeUnit.SECONDS));
            for(int i=0;i<4;i++)pool.execute(()->{});
            ExportService actual=f.makeService(pool,root);
            ExportJob job=actual.submit(new ExportRequest(5L,1,"mp4"),1L);
            assertEquals("EXPORT_QUEUE_FULL",actual.get(job.id(),1L).errorCode());
            assertEquals(4,pool.getThreadPoolExecutor().getQueue().size());assertEquals(1,pool.getPoolSize());
        } finally {release.countDown();pool.shutdown();}
    }
    @Test void completionWriteFailureFailsJobAndRemovesUploadedObject() {
        f.store.failCompletion=true;ExportJob job=f.submit();
        assertDoesNotThrow(()->f.runNext());
        assertEquals("FAILED",f.service.get(job.id(),1L).state());assertTrue(f.storedResults.isEmpty());
    }
    @Test void transientClaimFailureIsPersistedAndExplicitRetryCanRun() {
        f.store.failStart=true;ExportJob job=f.submit();
        assertDoesNotThrow(()->f.runNext());
        assertEquals("FAILED",f.service.get(job.id(),1L).state());
        assertEquals("EXPORT_FAILED",f.service.get(job.id(),1L).errorCode());
        assertTrue(f.uploads.isEmpty());
        assertEquals(2,f.submit().attempt());f.runNext();
        assertEquals("COMPLETED",f.service.get(job.id(),1L).state());
    }
    @Test void ambiguousClaimFailureAlsoFailsTheClaimedAttempt() {
        f.store.claimThenFail=true;ExportJob job=f.submit();
        assertDoesNotThrow(()->f.runNext());
        assertEquals("FAILED",f.service.get(job.id(),1L).state());assertEquals(2,f.submit().attempt());
    }
    @Test void completionFailureWithFailedObjectDeletionRetainsSafeCleanupStateForRetry() {
        f.store.failCompletion=true;f.storageCleanupUnavailable=true;ExportJob job=f.submit();
        assertDoesNotThrow(()->f.runNext());
        ExportJob failed=f.service.get(job.id(),1L);
        assertEquals("FAILED",failed.state());assertEquals("EXPORT_STORAGE_CLEANUP_FAILED",failed.errorCode());
        assertEquals("http://minio/bucket/result.mp4",f.store.rows.get(job.id()).getResultObject());
        assertFalse(failed.errorMessage().contains("secret"));
        BusinessException unavailable=assertThrows(BusinessException.class,()->f.submit());
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,unavailable.errorCode());
        assertEquals(1,f.service.get(job.id(),1L).attempt());assertTrue(f.queue.isEmpty());
        f.storageCleanupUnavailable=false;
        assertEquals(2,f.submit().attempt());assertTrue(f.storedResults.isEmpty());f.runNext();
        assertEquals("COMPLETED",f.service.get(job.id(),1L).state());
    }
    @Test void ambiguousSuccessfulCompletionPreservesItsReferencedUploadedObject() {
        f.store.completeThenFail=true;ExportJob job=f.submit();
        assertDoesNotThrow(()->f.runNext());
        assertEquals("COMPLETED",f.service.get(job.id(),1L).state());
        assertEquals(Set.of("http://minio/bucket/result.mp4"),f.storedResults);
        assertEquals("signed-result",f.service.download(job.id(),1L));
    }

    static class Fixture {
        final MemoryExports store=new MemoryExports();
        final List<Runnable> queue=new ArrayList<>(); final List<String> uploads=new ArrayList<>();
        final Set<String> storedResults=new HashSet<>();
        final EditorService editor=mock(EditorService.class);
        final MediaService media=mock(MediaService.class); final MinioUtils minio=mock(MinioUtils.class);
        final FfmpegMediaExporter exporter=mock(FfmpegMediaExporter.class);
        final FfmpegMediaTools tools=mock(FfmpegMediaTools.class);
        final ExportService service;
        List<String> selection=List.of("a","b"); int version=1;
        boolean reject,deleted,storageCleanupUnavailable; String sourcePath="http://minio/bucket/source.mp4",contentHash;
        Fixture(Path root) throws Exception {
            when(editor.requireProject(anyLong(),anyLong())).thenAnswer(c->{
                if(deleted) throw new SecurityException("deleted");
                return new EditProject(c.getArgument(0),7L,9L,version,selection,LocalDateTime.now());
            });
            when(editor.requireTranscript(anyLong(),anyLong())).thenReturn(new TranscriptDocument(9L,7L,1,"SUBTITLE_IMPORT",6000,"COMPLETED",null,
                List.of(new TimedCue("a",0,1000,"red1"),new TimedCue("b",1000,2000,"red2"),new TimedCue("c",4000,6000,"blue"))));
            when(media.requireOwnedMedia(anyLong(),anyLong())).thenAnswer(c->{
                if(deleted) throw new SecurityException("deleted");
                MediaFile m=new MediaFile();m.setId(7L);m.setUserId(c.getArgument(1));m.setFilePath(sourcePath);m.setContentHash(contentHash);return m;
            });
            doAnswer(c->{ c.<OutputStream>getArgument(1).write("source".getBytes());return null; }).when(minio).copyManagedFileTo(anyString(),any());
            doAnswer(c->{
                List<ClipRange> ranges=c.getArgument(1);Files.writeString(c.getArgument(2),ranges.stream().map(r->r.startMs()+":"+r.endMs()).reduce((a,b)->a+","+b).orElse(""));return null;
            }).when(exporter).export(any(),anyList(),any());
            when(tools.probeDurationMs(any())).thenReturn(2000L);
            when(minio.uploadLocalFile(any(File.class),anyString(),anyString())).thenAnswer(c->{uploads.add(Files.readString(c.<File>getArgument(0).toPath()));storedResults.add("http://minio/bucket/result.mp4");return "http://minio/bucket/result.mp4";});
            doAnswer(c->{if(storageCleanupUnavailable)throw new IllegalStateException("secret storage cleanup failure");storedResults.remove(c.<String>getArgument(0));return null;}).when(minio).removeFile(anyString());
            when(minio.readableSource("http://minio/bucket/result.mp4")).thenReturn("signed-result");
            service=makeService(r->{if(reject)throw new RejectedExecutionException("secret");queue.add(r);},root);
        }
        ExportService makeService(Executor executor,Path root) {return new ExportService(store,editor,media,minio,exporter,tools,new ObjectMapper().findAndRegisterModules(),executor,new MemoryTransactions(),root.toString());}
        ExportJob submit() { return service.submit(new ExportRequest(5L,1,"mp4"),1L); }
        void runNext() { queue.removeFirst().run(); }
    }
    static class MemoryTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() {return new Object();}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
    static class MemoryExports implements ExportStore {
        final Map<String,ExportRow> rows=new LinkedHashMap<>(); boolean duplicateNext,failCompletion,failStart,claimThenFail,completeThenFail;
        public Long lockExportMedia(Long mediaId,Long userId) { return mediaId; }
        public int insertExport(ExportRow row) {
            if(duplicateNext) {duplicateNext=false;row.setId("winner");rows.put(row.getId(),row);throw new DuplicateKeyException("duplicate");}
            if(findExportByHash(row.getUserId(),row.getRequestHash())!=null)throw new DuplicateKeyException("duplicate");
            rows.put(row.getId(),row);return 1;
        }
        public ExportRow findExport(String id,Long userId) {var row=rows.get(id);return row!=null&&row.getUserId().equals(userId)?row:null;}
        public ExportRow findExportByHash(Long userId,String hash) {return rows.values().stream().filter(r->r.getUserId().equals(userId)&&r.getRequestHash().equals(hash)).findFirst().orElse(null);}
        public ExportRow latestExport(Long projectId,Long userId) {return rows.values().stream().filter(r->r.getProjectId().equals(projectId)&&r.getUserId().equals(userId)).max(Comparator.comparing(ExportRow::getUpdatedAt)).orElse(null);}
        public int retryExport(String id,Long userId,int attempt,LocalDateTime now) {var r=findExport(id,userId);if(r==null||!r.getState().equals("FAILED")||r.getAttempt()!=attempt||attempt>=3)return 0;r.setAttempt(attempt+1);r.setState("QUEUED");r.setErrorCode(null);r.setErrorMessage(null);r.setUpdatedAt(now);return 1;}
        public int startExport(String id,int attempt,LocalDateTime now) {if(failStart){failStart=false;throw new IllegalStateException("transient claim failure");}var r=rows.get(id);if(r==null||r.getAttempt()!=attempt||!r.getState().equals("QUEUED"))return 0;r.setState("PROCESSING");r.setUpdatedAt(now);if(claimThenFail){claimThenFail=false;throw new IllegalStateException("ambiguous claim write");}return 1;}
        public int completeExport(String id,int attempt,String result,LocalDateTime now) {if(failCompletion){failCompletion=false;throw new IllegalStateException("temporary database failure");}var r=rows.get(id);if(r==null||r.getAttempt()!=attempt||!r.getState().equals("PROCESSING"))return 0;r.setState("COMPLETED");r.setResultObject(result);r.setUpdatedAt(now);if(completeThenFail){completeThenFail=false;throw new IllegalStateException("ambiguous completion write");}return 1;}
        public int failExport(String id,int attempt,String code,String message,LocalDateTime now) {var r=rows.get(id);if(r==null||r.getAttempt()!=attempt||!List.of("QUEUED","PROCESSING").contains(r.getState()))return 0;r.setState("FAILED");r.setErrorCode(code);r.setErrorMessage(message);r.setUpdatedAt(now);return 1;}
        public int failExportWithResult(String id,int attempt,String result,String code,String message,LocalDateTime now) {int changed=failExport(id,attempt,code,message,now);if(changed==1)rows.get(id).setResultObject(result);return changed;}
        public int recordExportCleanupFailure(String id,int attempt,String result,LocalDateTime now) {var r=rows.get(id);if(r==null||r.getAttempt()!=attempt||!r.getState().equals("FAILED"))return 0;r.setResultObject(result);r.setErrorCode("EXPORT_STORAGE_CLEANUP_FAILED");r.setErrorMessage("导出结果清理失败，请稍后重试");r.setUpdatedAt(now);return 1;}
        public int interruptExports(LocalDateTime now) {int count=0;for(var r:rows.values())if(failExport(r.getId(),r.getAttempt(),"EXPORT_INTERRUPTED","导出服务重启，任务已中断，请重试",now)==1)count++;return count;}
    }
}
