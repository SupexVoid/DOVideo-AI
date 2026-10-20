package com.example.server.editor;

import com.example.server.editor.EditorModels.*;
import com.example.server.editor.EditorStore.*;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.common.ErrorCode;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EditorServiceTest {
    @TempDir Path root;
    Fixture f;
    @BeforeEach void init() { f = new Fixture(root); }
    static final String SRT = "1\n00:00:00,000 --> 00:00:01,000\nhello";

    @Test void foreignMediaCannotBeImportedReadOrSaved() {
        assertThrows(SecurityException.class, () -> f.service.importTranscript(new ImportTranscriptRequest(8L,"srt",SRT),1L));
        assertThrows(SecurityException.class, () -> f.service.getTranscript(8L,null,1L));
        assertThrows(SecurityException.class, () -> f.service.getProject(8L,1L));
        assertThrows(SecurityException.class, () -> f.service.saveProject(new SaveProjectRequest(null,8L,1L,0,List.of()),1L));
        assertTrue(f.store.transcripts.isEmpty());
        assertTrue(f.store.projects.isEmpty());
    }
    @Test void importUsesRealProbeDurationAndRemovesOnlyOwnTempDirectory() throws Exception {
        Path unrelated = Files.writeString(root.resolve("keep.txt"),"keep");
        TranscriptDocument doc = f.service.importTranscript(new ImportTranscriptRequest(7L,"srt",SRT),1L);
        assertEquals(6000,doc.durationMs());
        assertEquals("COMPLETED",doc.state());
        assertEquals("hello",doc.cues().getFirst().text());
        try (var children=Files.list(root)) { assertEquals(List.of(unrelated),children.toList()); }
    }
    @Test void invalidSelectedIdDoesNotCreateProject() {
        TranscriptDocument doc=f.importDoc();
        assertThrows(IllegalArgumentException.class,()->f.service.saveProject(new SaveProjectRequest(null,7L,doc.id(),0,List.of("unknown")),1L));
        assertTrue(f.store.projects.isEmpty());
    }
    @Test void staleVersionCannotOverwriteNewSelectionAndEmptyDraftPersists() {
        TranscriptDocument doc=f.importDoc();
        EditProject first=f.service.saveProject(new SaveProjectRequest(null,7L,doc.id(),0,List.of("cue-1","cue-1")),1L);
        assertEquals(1,first.version());
        assertEquals(List.of("cue-1"),first.keptCueIds());
        EditProject second=f.service.saveProject(new SaveProjectRequest(first.id(),7L,doc.id(),1,List.of()),1L);
        BusinessException error=assertThrows(BusinessException.class,()->f.service.saveProject(new SaveProjectRequest(first.id(),7L,doc.id(),1,List.of("cue-1")),1L));
        assertEquals(ErrorCode.CONFLICT,error.errorCode());
        assertEquals(2,second.version());
        assertEquals(List.of(),f.service.getProject(7L,1L).keptCueIds());
    }
    @Test void reimportCreatesNewRevisionAndOldProjectStillLoadsOldTranscript() {
        TranscriptDocument old=f.importDoc();
        EditProject project=f.service.saveProject(new SaveProjectRequest(null,7L,old.id(),0,List.of("cue-1")),1L);
        TranscriptDocument newer=f.service.importTranscript(new ImportTranscriptRequest(7L,"srt",SRT.replace("hello","new")),1L);
        assertNotEquals(old.id(),newer.id());
        assertEquals(2,newer.revision());
        assertEquals(newer.id(),f.service.getTranscript(7L,null,1L).id());
        assertEquals(old.id(),f.service.getProject(7L,1L).transcriptId());
        assertEquals("hello",f.service.getTranscript(7L,project.transcriptId(),1L).cues().getFirst().text());
    }
    @Test void explicitTranscriptCannotCrossMediaOrUserAndEmptyDraftValidatesWholeTimeline() {
        TranscriptDocument doc=f.importDoc();
        TranscriptRow row=f.store.transcripts.get(doc.id());
        row.setMediaId(8L);
        assertThrows(SecurityException.class,()->f.service.saveProject(new SaveProjectRequest(null,7L,doc.id(),0,List.of()),1L));
        assertThrows(SecurityException.class,()->f.service.getTranscript(7L,doc.id(),1L));
        row.setMediaId(7L);
        row.setCuesJson("[{\"id\":\"bad\",\"startMs\":0,\"endMs\":9000,\"text\":\"bad\"}]");
        assertThrows(IllegalArgumentException.class,()->f.service.saveProject(new SaveProjectRequest(null,7L,doc.id(),0,List.of()),1L));
        assertTrue(f.store.projects.isEmpty());
    }
    @Test void absentDocumentsReturnNullAfterOwnershipCheck() {
        assertNull(f.service.getTranscript(7L,null,1L));
        assertNull(f.service.getProject(7L,1L));
    }
    @Test void oversizedSourceKeepsPayloadErrorWhenStorageWrapsTheStreamFailure() throws Exception {
        doThrow(new IllegalStateException("storage wrapper", new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE,"too large")))
            .when(f.minio).copyManagedFileTo(anyString(),any());
        BusinessException error=assertThrows(BusinessException.class,()->f.importDoc());
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE,error.errorCode());
        assertTrue(f.store.transcripts.isEmpty());
        try(var children=Files.list(root)) { assertEquals(0,children.count()); }
    }

    @Test void missingRealFfprobeReturnsServiceUnavailableWithoutPersistenceOrLeakedTempFiles() throws Exception {
        Path unrelated=Files.writeString(root.resolve("keep.txt"),"keep");
        Fixture actualTools=new Fixture(root,new FfmpegMediaTools(new ObjectMapper(),root.resolve("missing-tools").toString()));
        doAnswer(call->{
            java.io.OutputStream destination=call.getArgument(1);
            destination.write("managed source bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return null;
        }).when(actualTools.minio).copyManagedFileTo(anyString(),any());
        BusinessException failure=assertThrows(BusinessException.class,actualTools::importDoc);
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,failure.errorCode());
        assertEquals(503,new com.example.server.controller.ApiExceptionHandler().business(failure).getStatusCode().value());
        assertTrue(actualTools.store.transcripts.isEmpty());
        assertTrue(actualTools.store.projects.isEmpty());
        assertEquals("keep",Files.readString(unrelated));
        try(var children=Files.list(root)) { assertEquals(List.of(unrelated),children.toList()); }
    }

    @Test void framesRequireOwnershipValidatePositionAndCleanTheDownloadedSource() throws Exception {
        assertThrows(SecurityException.class,()->f.service.frame(8L,0,1L));
        assertThrows(IllegalArgumentException.class,()->f.service.frame(7L,-1,1L));
        assertThrows(IllegalArgumentException.class,()->f.service.frame(7L,6000,1L));
        byte[] jpeg={(byte)255,(byte)216,1};
        FfmpegMediaTools frameTools=mock(FfmpegMediaTools.class);
        when(frameTools.probeDurationMs(any(Path.class))).thenReturn(6000L);
        when(frameTools.extractFrame(any(Path.class),eq(4000L))).thenReturn(jpeg);
        Fixture frames=new Fixture(root,frameTools);
        assertArrayEquals(jpeg,frames.service.frame(7L,4000,1L));
        try(var children=Files.list(root)) { assertEquals(0,children.count()); }
    }

    static class Fixture {
        final MemoryStore store=new MemoryStore();
        final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        final MinioUtils minio=mock(MinioUtils.class);
        final EditorService service;
        Fixture(Path root) { this(root,null); }
        Fixture(Path root,FfmpegMediaTools suppliedTools) {
            MediaService media=mock(MediaService.class);
            MediaFile owned=new MediaFile(); owned.setId(7L); owned.setUserId(1L); owned.setFilePath("http://minio/bucket/source.mp4");
            when(media.requireOwnedMedia(anyLong(),anyLong())).thenAnswer(call->{
                if (call.<Long>getArgument(0)!=7L || call.<Long>getArgument(1)!=1L) throw new SecurityException("无权访问该文件");
                return owned;
            });
            FfmpegMediaTools tools=suppliedTools;
            if(tools==null) {
                tools=mock(FfmpegMediaTools.class);
                when(tools.probeDurationMs(any(Path.class))).thenReturn(6000L);
            }
            service=new EditorService(store,media,minio,tools,json,root.toString());
        }
        TranscriptDocument importDoc() { return service.importTranscript(new ImportTranscriptRequest(7L,"srt",SRT),1L); }
    }
    static class MemoryStore implements EditorStore {
        final Map<Long,TranscriptRow> transcripts=new LinkedHashMap<>();
        final Map<Long,ProjectRow> projects=new LinkedHashMap<>();
        public Long lockOwnedMedia(Long mediaId,Long userId) { return mediaId==7L && userId==1L ? mediaId:null; }
        public int nextRevision(Long mediaId) { return transcripts.values().stream().filter(r->r.getMediaId().equals(mediaId)).mapToInt(TranscriptRow::getRevision).max().orElse(0)+1; }
        public int insertTranscript(TranscriptRow row) { row.setId((long)transcripts.size()+1); transcripts.put(row.getId(),row); return 1; }
        public TranscriptRow findTranscript(Long id,Long userId) { TranscriptRow r=transcripts.get(id);return r!=null && r.getUserId().equals(userId)?r:null; }
        public TranscriptRow latestTranscript(Long mediaId,Long userId) { return transcripts.values().stream().filter(r->r.getMediaId().equals(mediaId)&&r.getUserId().equals(userId)).max(Comparator.comparingInt(TranscriptRow::getRevision)).orElse(null); }
        public int insertProject(ProjectRow row) { row.setId((long)projects.size()+1);projects.put(row.getId(),row);return 1; }
        public ProjectRow findProject(Long id,Long userId) { ProjectRow r=projects.get(id);return r!=null&&r.getUserId().equals(userId)?r:null; }
        public ProjectRow latestProject(Long mediaId,Long userId) { return projects.values().stream().filter(r->r.getMediaId().equals(mediaId)&&r.getUserId().equals(userId)).max(Comparator.comparing(ProjectRow::getUpdatedAt)).orElse(null); }
        public int updateProject(ProjectRow row,int expectedVersion) { ProjectRow old=projects.get(row.getId());if(old==null||!old.getUserId().equals(row.getUserId())||old.getVersion()!=expectedVersion)return 0;projects.put(row.getId(),row);return 1; }
    }
}
