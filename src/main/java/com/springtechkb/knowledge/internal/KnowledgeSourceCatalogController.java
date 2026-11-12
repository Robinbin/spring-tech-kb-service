package com.springtechkb.knowledge.internal;

import java.util.UUID;

import jakarta.validation.Valid;
import com.springtechkb.JwtPrincipalResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/knowledge/crawl")
class KnowledgeSourceCatalogController {

    private final KnowledgeSourceCatalogService catalog;

    KnowledgeSourceCatalogController(KnowledgeSourceCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/catalog")
    @PreAuthorize("hasRole('ADMIN')")
    java.util.List<KnowledgeSourceCatalogService.CatalogItem> catalog() {
        return catalog.catalog();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    KnowledgeSourceCatalogService.CrawlRunResult crawl(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody KnowledgeSourceCatalogService.CrawlRequest request) {
        UUID operatorId = JwtPrincipalResolver.userId(jwt);
        try {
            return catalog.crawl(request, operatorId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }
}
