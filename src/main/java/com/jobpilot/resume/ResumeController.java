package com.jobpilot.resume;

import com.jobpilot.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {
    private final ResumeService service;
    public ResumeController(ResumeService service) { this.service = service; }

    @GetMapping public ApiResponse<ResumeService.Catalog> list() { return ApiResponse.ok(service.list()); }

    @GetMapping("/{id}") public ApiResponse<ResumeDocument> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping(value = "/import", consumes = "application/octet-stream")
    public ApiResponse<ResumeDocument> upload(@RequestParam String filename,
                                             @RequestParam(required = false) String replaceId,
                                             HttpServletRequest request) throws java.io.IOException {
        if (request.getContentLengthLong() > ResumeTextExtractor.MAX_FILE_BYTES) {
            return ApiResponse.fail("简历文件不能超过 10 MB");
        }
        byte[] data = request.getInputStream().readNBytes(ResumeTextExtractor.MAX_FILE_BYTES + 1);
        return ApiResponse.ok(service.importFile(filename, data, replaceId));
    }

    public record TextRequest(String text) { }
    public record AnalysisRequest(String targetRole) { }

    @PutMapping("/{id}/text") public ApiResponse<ResumeDocument> text(@PathVariable String id, @RequestBody TextRequest body) {
        return ApiResponse.ok(service.updateText(id, body.text()));
    }

    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/analyze") public ApiResponse<ResumeDocument> analyze(@PathVariable String id,
                                                                         @RequestBody(required = false) AnalysisRequest body) {
        return ApiResponse.ok(service.analyze(id, body == null ? "" : body.targetRole()));
    }

    @PostMapping("/{id}/apply-persona") public ApiResponse<Void> apply(@PathVariable String id) {
        service.applyPersona(id);
        return ApiResponse.ok();
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ApiResponse<Void> invalid(RuntimeException error) { return ApiResponse.fail(error.getMessage()); }
}
