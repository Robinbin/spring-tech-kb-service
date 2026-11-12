package com.springtechkb.generation.internal;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static com.springtechkb.generation.internal.GenerationDtos.*;

/**
 * Authenticated generation API. Creation returns 201; projects return application/zip with X-Generation-Id.
 * Retrieval and project downloads are owner-only (404 for another caller); revoked evidence returns 410.
 * Examples without approved source evidence return 422 NO_APPROVED_SOURCES.
 */
@RestController
@RequestMapping("/api/generation")
class GenerationController {
    private final GenerationService service;

    GenerationController(GenerationService service) { this.service = service; }

    @PostMapping("/examples")
    ResponseEntity<Output> example(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ExampleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.example(owner(jwt), request));
    }

    @PostMapping("/comparisons")
    ResponseEntity<Output> compare(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ComparisonRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.compare(owner(jwt), request));
    }

    @GetMapping("/{id}")
    Output get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return service.get(owner(jwt), id); }

    @PostMapping(value = "/projects", produces = "application/zip")
    ResponseEntity<byte[]> project(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProjectRequest request) {
        return archive(service.project(owner(jwt), request), HttpStatus.CREATED);
    }

    @GetMapping(value = "/{id}/project", produces = "application/zip")
    ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return archive(service.download(owner(jwt), id), HttpStatus.OK);
    }

    private static ResponseEntity<byte[]> archive(Archive archive, HttpStatus status) {
        return ResponseEntity.status(status).contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(archive.filename()).build().toString())
                .header("X-Generation-Id", archive.id().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(archive.bytes());
    }

    private static UUID owner(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    }
}
