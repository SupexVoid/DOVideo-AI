package com.example.server.editor;

import com.example.server.common.Result;
import com.example.server.editor.EditorModels.TranscriptDocument;
import com.example.server.service.AuthService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/media/editor/transcripts")
public class TimedTranscriptionController {
    private final TimedTranscriptionService service;
    public TimedTranscriptionController(TimedTranscriptionService service){this.service=service;}
    public record TranscribeRequest(Long mediaId) {}
    @PostMapping("/transcribe")
    public Result<TranscriptDocument> transcribe(@RequestBody TranscribeRequest request,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId){
        if(request==null)throw new IllegalArgumentException("转写参数不能为空");
        return Result.ok(service.submit(request.mediaId(),userId));
    }
    @GetMapping("/status")
    public Result<TranscriptDocument> status(@RequestParam Long mediaId,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId){return Result.ok(service.status(mediaId,userId));}
}
