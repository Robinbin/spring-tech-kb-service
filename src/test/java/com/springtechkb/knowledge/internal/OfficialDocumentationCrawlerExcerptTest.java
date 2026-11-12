package com.springtechkb.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OfficialDocumentationCrawlerExcerptTest {

    @Test
    void boundsPageExcerptAndMarksTruncation() {
        String text = "paragraph\n\n".repeat(2_000);

        String excerpt = OfficialDocumentationCrawler.boundedExcerpt(text);

        assertThat(excerpt).hasSizeLessThanOrEqualTo(12_000);
        assertThat(excerpt).endsWith("[Excerpt truncated at the configured page limit.]");
    }

    @Test
    void preservesShortPageTextWithoutAddingAnExcerptMarker() {
        assertThat(OfficialDocumentationCrawler.boundedExcerpt("A short official page"))
                .isEqualTo("A short official page");
    }
}
