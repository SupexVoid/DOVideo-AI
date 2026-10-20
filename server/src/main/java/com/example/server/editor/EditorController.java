package com.example.server.editor;
import com.example.server.common.Result;
import com.example.server.editor.EditorModels.*;
import com.example.server.service.AuthService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/media/editor")
public class EditorController {
    private final EditorService service;
    public EditorController(EditorService service) {this.service=service;}
    @PostMapping("/transcripts/import")
    public Result<TranscriptDocument> importTranscript(@RequestBody ImportTranscriptRequest request,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {return Result.ok(service.importTranscript(request,userId));}
    @GetMapping("/transcripts")
    public Result<TranscriptDocument> getTranscript(@RequestParam Long mediaId,@RequestParam(required=false) Long transcriptId,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {return Result.ok(service.getTranscript(mediaId,transcriptId,userId));}
    @PostMapping("/projects")
    public Result<EditProject> saveProject(@RequestBody SaveProjectRequest request,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {return Result.ok(service.saveProject(request,userId));}
    @GetMapping("/projects")
    public Result<EditProject> getProject(@RequestParam Long mediaId,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {return Result.ok(service.getProject(mediaId,userId));}
    @GetMapping(value="/frame",produces="image/jpeg")
    public org.springframework.http.ResponseEntity<byte[]> frame(@RequestParam Long mediaId,@RequestParam long atMs,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
            .contentType(org.springframework.http.MediaType.IMAGE_JPEG).body(service.frame(mediaId,atMs,userId));
    }
}
