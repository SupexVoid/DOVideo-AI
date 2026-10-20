package com.example.server.editor;

import com.example.server.controller.ApiExceptionHandler;
import com.example.server.service.AuthService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EditorControllerTest {
    @TempDir Path root;
    MockMvc mvc;
    EditorServiceTest.Fixture f;
    @BeforeEach void init() {
        f=new EditorServiceTest.Fixture(root);
        mvc=MockMvcBuilders.standaloneSetup(new EditorController(f.service)).setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @Test void importSaveReloadAndConflictUseAuthenticatedUserAndResultEnvelope() throws Exception {
        mvc.perform(post("/media/editor/transcripts/import").requestAttr(AuthService.REQUEST_USER_ID,1L).contentType(MediaType.APPLICATION_JSON)
            .content(f.json.writeValueAsString(new EditorModels.ImportTranscriptRequest(7L,"srt",EditorServiceTest.SRT))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.durationMs").value(6000));
        mvc.perform(post("/media/editor/projects").requestAttr(AuthService.REQUEST_USER_ID,1L).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mediaId\":7,\"transcriptId\":1,\"version\":0,\"keptCueIds\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(get("/media/editor/projects").param("mediaId","7").requestAttr(AuthService.REQUEST_USER_ID,1L))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.keptCueIds").isEmpty());
        mvc.perform(post("/media/editor/projects").requestAttr(AuthService.REQUEST_USER_ID,1L).contentType(MediaType.APPLICATION_JSON)
            .content("{\"id\":1,\"mediaId\":7,\"transcriptId\":1,\"version\":0,\"keptCueIds\":[\"cue-1\"]}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40900));
    }
    @Test void foreignUserCannotReadImportOrSave() throws Exception {
        f.importDoc();
        mvc.perform(get("/media/editor/transcripts").param("mediaId","7").requestAttr(AuthService.REQUEST_USER_ID,2L)).andExpect(status().isForbidden());
        mvc.perform(post("/media/editor/transcripts/import").requestAttr(AuthService.REQUEST_USER_ID,2L).contentType(MediaType.APPLICATION_JSON)
            .content(f.json.writeValueAsString(new EditorModels.ImportTranscriptRequest(7L,"srt",EditorServiceTest.SRT)))).andExpect(status().isForbidden());
        mvc.perform(post("/media/editor/projects").requestAttr(AuthService.REQUEST_USER_ID,2L).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mediaId\":7,\"transcriptId\":1,\"version\":0,\"keptCueIds\":[]}")).andExpect(status().isForbidden());
    }
    @Test void explicitOldTranscriptQueryRestoresRevisionAndMissingVersionFails() throws Exception {
        f.importDoc(); f.service.importTranscript(new EditorModels.ImportTranscriptRequest(7L,"srt",EditorServiceTest.SRT.replace("hello","new")),1L);
        mvc.perform(get("/media/editor/transcripts").param("mediaId","7").param("transcriptId","1").requestAttr(AuthService.REQUEST_USER_ID,1L))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1)).andExpect(jsonPath("$.data.cues[0].text").value("hello"));
        mvc.perform(post("/media/editor/projects").requestAttr(AuthService.REQUEST_USER_ID,1L).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mediaId\":7,\"transcriptId\":1,\"keptCueIds\":[]}"))
            .andExpect(status().isBadRequest());
    }
}
