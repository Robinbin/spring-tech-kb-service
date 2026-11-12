package com.springtechkb.search.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/** Operational Prompt Registry API; every operation requires a configured admin secret. */
@RestController
@RequestMapping("/api/internal/prompts")
class PromptRegistryController {

    private static final String ADMIN_HEADER = "X-Prompt-Admin-Token";

    private final PromptRegistryService prompts;
    private final String adminToken;

    PromptRegistryController(
            PromptRegistryService prompts,
            @Value("${springtechkb.rag.prompt-registry.admin-token:}") String adminToken) {
        this.prompts = prompts;
        this.adminToken = adminToken;
    }

    @GetMapping("/{name}")
    java.util.List<PromptRegistryService.PromptVersion> versions(
            @PathVariable String name,
            @RequestHeader(name = ADMIN_HEADER, required = false) String token) {
        authorize(token);
        return prompts.versions(name);
    }

    @PostMapping("/{name}/versions")
    PromptRegistryService.PromptVersion create(
            @PathVariable String name,
            @RequestHeader(name = ADMIN_HEADER, required = false) String token,
            @RequestBody CreatePromptRequest request) {
        authorize(token);
        return prompts.createVersion(name, request.content(), request.modelTier(), request.description());
    }

    @GetMapping("/{name}/diff")
    PromptRegistryService.PromptDiff diff(
            @PathVariable String name, @RequestParam int from, @RequestParam int to,
            @RequestHeader(name = ADMIN_HEADER, required = false) String token) {
        authorize(token);
        return prompts.diff(name, from, to);
    }

    @PutMapping("/{name}/canary")
    void canary(
            @PathVariable String name,
            @RequestHeader(name = ADMIN_HEADER, required = false) String token,
            @RequestBody CanaryRequest request) {
        authorize(token);
        prompts.setCanary(name, request.version(), request.trafficWeight());
    }

    @PostMapping("/{name}/{version}/activate")
    void activate(
            @PathVariable String name,
            @PathVariable int version,
            @RequestHeader(name = ADMIN_HEADER, required = false) String token) {
        authorize(token);
        prompts.activate(name, version);
    }

    private void authorize(String providedToken) {
        if (adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Prompt Registry admin API is disabled");
        }
        if (providedToken == null || !MessageDigest.isEqual(
                adminToken.getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid Prompt Registry admin token");
        }
    }

    record CreatePromptRequest(String content, String modelTier, String description) {}

    record CanaryRequest(int version, int trafficWeight) {}
}
