package com.springtechkb.knowledge.internal;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.springtechkb.HttpErrors;
import com.springtechkb.knowledge.CrawledDocument;
import com.springtechkb.knowledge.KnowledgeSource;
import com.springtechkb.knowledge.SourceAdmission;
import com.springtechkb.knowledge.SourceLicense;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Executes a capped crawl only for source URLs in the reviewed official-document catalog. */
@Service
class KnowledgeSourceCatalogService {

    static final int MAX_SOURCES_PER_RUN = 3;

    private final JdbcTemplate jdbc;
    private final OfficialDocumentationCrawler crawler;
    private final KnowledgeIngestionService ingestion;

    KnowledgeSourceCatalogService(
            JdbcTemplate jdbc,
            OfficialDocumentationCrawler crawler,
            KnowledgeIngestionService ingestion) {
        this.jdbc = jdbc;
        this.crawler = crawler;
        this.ingestion = ingestion;
    }

    List<CatalogItem> catalog() {
        return jdbc.query("""
                SELECT source_key, title, source_uri, spring_module, version, license_type, license_url
                FROM knowledge_source_catalog
                WHERE enabled = TRUE
                ORDER BY source_key
                """, (rs, row) -> new CatalogItem(
                rs.getString("source_key"), rs.getString("title"), rs.getString("source_uri"),
                rs.getString("spring_module"), rs.getString("version"),
                rs.getString("license_type"), rs.getString("license_url")));
    }

    @Transactional
    CrawlRunResult crawl(CrawlRequest request, UUID operatorId) {
        if (request == null || request.sourceKeys() == null || request.sourceKeys().isEmpty()
                || request.sourceKeys().size() > MAX_SOURCES_PER_RUN
                || request.maxSources() == null || request.maxSources() < 1
                || request.maxSources() > MAX_SOURCES_PER_RUN
                || request.sourceKeys().size() > request.maxSources()
                || request.sourceKeys().stream().distinct().count() != request.sourceKeys().size()
                || operatorId == null || request.approvalReference() == null
                || request.approvalReference().isBlank() || request.approvalReference().length() > 500) {
            throw HttpErrors.badRequest(
                    "Provide 1-3 distinct catalog sources within maxSources and a legal approval reference");
        }

        List<CatalogRow> selected = new java.util.ArrayList<>();
        for (String sourceKey : request.sourceKeys()) {
            List<CatalogRow> matches = jdbc.query("""
                    SELECT source_key, title, source_uri, spring_module, version, license_type, license_url
                    FROM knowledge_source_catalog
                    WHERE source_key = ? AND enabled = TRUE
                    """, (rs, row) -> new CatalogRow(
                    rs.getString("source_key"), rs.getString("title"), URI.create(rs.getString("source_uri")),
                    rs.getString("spring_module"), rs.getString("version"),
                    SourceLicense.valueOf(rs.getString("license_type")), rs.getString("license_url")),
                    sourceKey);
            if (matches.isEmpty()) {
                throw HttpErrors.badRequest("Unknown or disabled catalog source: " + sourceKey);
            }
            selected.add(matches.getFirst());
        }

        List<CrawlItemResult> results = selected.stream().map(row -> crawlOne(row, request.approvalReference(),
                operatorId)).toList();
        return new CrawlRunResult(results.size(), results);
    }

    private CrawlItemResult crawlOne(CatalogRow row, String approvalReference, UUID operatorId) {
        List<StoredValidators> validators = jdbc.query("""
                SELECT etag, last_modified
                FROM knowledge_source
                WHERE url = ?
                """, (rs, index) -> new StoredValidators(rs.getString("etag"), rs.getString("last_modified")),
                row.uri().toString());
        StoredValidators previous = validators.isEmpty() ? new StoredValidators(null, null) : validators.getFirst();
        SourceAdmission admission = new SourceAdmission(
                row.uri(), KnowledgeSource.SPRING_OFFICIAL_DOCS, row.license(), row.title(), "Spring Team",
                row.springModule(), row.version(), row.licenseUrl(), operatorId.toString(), approvalReference);
        OfficialDocumentationCrawler.CrawlResult result =
                crawler.crawl(admission, previous.etag(), previous.lastModified());
        if (result.notModified()) {
            jdbc.update("""
                    UPDATE knowledge_source_catalog
                    SET last_crawl_at = now(), last_crawl_status = 'UNCHANGED'
                    WHERE source_key = ?
                    """, row.key());
            return new CrawlItemResult(row.key(), "UNCHANGED", null, 0, null);
        }
        CrawledDocument document = result.document();
        KnowledgeIngestionService.IngestionResult indexed = ingestion.ingest(admission, document);
        jdbc.update("""
                UPDATE knowledge_source_catalog
                SET last_crawl_at = now(), last_crawl_status = 'INGESTED'
                WHERE source_key = ?
                """, row.key());
        return new CrawlItemResult(row.key(), "INGESTED", indexed.sourceId(), indexed.chunkCount(),
                indexed.contentHash());
    }

    record CrawlRequest(
            @NotEmpty @Size(max = MAX_SOURCES_PER_RUN) List<@NotNull @Size(max = 80) String> sourceKeys,
            @NotNull @Min(1) @Max(MAX_SOURCES_PER_RUN) Integer maxSources,
            @NotNull @Size(min = 1, max = 500) String approvalReference) {}

    record CatalogItem(
            String sourceKey, String title, String sourceUri, String springModule,
            String version, String licenseType, String licenseUrl) {}

    record CrawlRunResult(int processedSources, List<CrawlItemResult> results) {}

    record CrawlItemResult(String sourceKey, String status, UUID sourceId, int chunkCount, String contentHash) {}

    private record CatalogRow(
            String key, String title, URI uri, String springModule, String version,
            SourceLicense license, String licenseUrl) {}

    private record StoredValidators(String etag, String lastModified) {}
}
