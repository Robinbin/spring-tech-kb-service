package com.springtechkb.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class KnowledgeSourceCatalogServiceTest {

    @Test
    void rejectsCrawlRequestAboveExplicitSourceCapBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var service = new KnowledgeSourceCatalogService(
                jdbc,
                new OfficialDocumentationCrawler(new SourceAdmissionPolicy(), Duration.ZERO),
                mock(KnowledgeIngestionService.class));

        assertThatThrownBy(() -> service.crawl(
                new KnowledgeSourceCatalogService.CrawlRequest(
                        List.of("spring-cloud", "spring-batch", "spring-integration", "extra"),
                        4, "verified-legal-review-ref"),
                UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsDuplicateCatalogKeys() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var service = new KnowledgeSourceCatalogService(
                jdbc,
                new OfficialDocumentationCrawler(new SourceAdmissionPolicy(), Duration.ZERO),
                mock(KnowledgeIngestionService.class));

        assertThatThrownBy(() -> service.crawl(
                new KnowledgeSourceCatalogService.CrawlRequest(
                        List.of("spring-cloud", "spring-cloud"), 2, "verified-legal-review-ref"),
                UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }
}
