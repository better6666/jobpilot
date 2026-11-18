package com.jobpilot.update;

import com.jobpilot.common.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/updates")
public class UpdateController {
    private final UpdateService service;

    public UpdateController(UpdateService service) { this.service = service; }

    @GetMapping
    public ApiResponse<UpdateService.Status> status() { return ApiResponse.ok(service.status()); }

    @PostMapping("/check")
    public ApiResponse<UpdateService.Status> check() { return ApiResponse.ok(service.refresh()); }

    public record InstallRequest(String version) { }

    @PostMapping(value = "/install", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UpdateService.Status> install(@RequestBody InstallRequest request) {
        try {
            return ApiResponse.ok(service.install(request.version()));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(error.getMessage());
        }
    }

    @PostMapping(value = "/acknowledge", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> acknowledge(@RequestBody InstallRequest request) {
        service.acknowledge(request.version());
        return ApiResponse.ok();
    }
}
