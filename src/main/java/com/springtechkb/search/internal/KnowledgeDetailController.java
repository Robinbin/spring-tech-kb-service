package com.springtechkb.search.internal;

import java.util.UUID;

import com.springtechkb.search.KnowledgeQueries;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class KnowledgeDetailController {

    private final KnowledgeQueries knowledgeQueries;

    KnowledgeDetailController(KnowledgeQueries knowledgeQueries) {
        this.knowledgeQueries = knowledgeQueries;
    }

    @GetMapping("/api/knowledge/{id}")
    KnowledgeDetail detail(@PathVariable UUID id) {
        return knowledgeQueries.chunkDetail(id)
                .map(d -> new KnowledgeDetail(
                        d.id(), d.content(), d.springModule(), d.springVersionMin(), d.springVersionMax(),
                        new DetailSource(d.source().id(), d.source().title(), d.source().url(),
                                d.source().license(), d.source().licenseUrl(), d.source().author())))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge chunk was not found"));
    }

    record KnowledgeDetail(
            UUID id, String content, String springModule, String springVersionMin, String springVersionMax,
            DetailSource source) {}

    record DetailSource(UUID id, String title, String url, String license, String licenseUrl, String author) {}
}
