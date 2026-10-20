package com.example.server.editor;
import com.example.server.common.Result;
import com.example.server.service.AuthService;
import com.example.server.editor.SmartSelectionModels.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/media/editor/selections")
public class SmartSelectionController {
 private final SmartSelectionService service;
 public SmartSelectionController(SmartSelectionService service){this.service=service;}
 @PostMapping public Result<Job> submit(@RequestBody Request request,@RequestAttribute(AuthService.REQUEST_USER_ID)Long userId){return Result.ok(service.submit(request,userId));}
 @GetMapping("/{id}") public Result<Job> get(@PathVariable String id,@RequestAttribute(AuthService.REQUEST_USER_ID)Long userId){return Result.ok(service.get(id,userId));}
 @GetMapping public Result<Job> latest(@RequestParam Long mediaId,@RequestParam Long transcriptId,@RequestAttribute(AuthService.REQUEST_USER_ID)Long userId){return Result.ok(service.latest(mediaId,transcriptId,userId));}
}
