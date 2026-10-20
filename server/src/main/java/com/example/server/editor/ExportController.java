package com.example.server.editor;
import com.example.server.common.Result;
import com.example.server.editor.ExportService.ExportJob;
import com.example.server.editor.ExportService.ExportRequest;
import com.example.server.service.AuthService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/media/editor")
public class ExportController {
    private final ExportService service;
    public ExportController(ExportService service) {this.service=service;}
    @PostMapping("/exports")
    public Result<ExportJob> submit(@RequestBody ExportRequest request,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {return Result.ok(service.submit(request,userId));}
    @GetMapping("/exports/{id}")
    public Result<ExportJob> get(@PathVariable String id,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {return Result.ok(service.get(id,userId));}
    @GetMapping("/exports")
    public Result<ExportJob> latest(@RequestParam Long projectId,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {return Result.ok(service.latest(projectId,userId));}
    @GetMapping("/exports/{id}/download")
    public Result<String> download(@PathVariable String id,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {return Result.ok(service.download(id,userId));}
}
