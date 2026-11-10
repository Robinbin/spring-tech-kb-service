package com.springtechkb.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.springtechkb.knowledge.CrawledDocument;
import com.springtechkb.knowledge.KnowledgeSource;
import com.springtechkb.knowledge.SourceAdmission;
import com.springtechkb.knowledge.SourceLicense;

class EpicDCoreTest {

    private final SourceAdmissionPolicy policy = new SourceAdmissionPolicy();

    @Test
    void acceptsOfficialSpringDocumentationWithEvidence() {
        policy.validate(admission("https://docs.spring.io/spring-framework/reference/core.html",
                KnowledgeSource.SPRING_OFFICIAL_DOCS, SourceLicense.OFFICIAL_DOCS), true);
    }

    @Test
    void rejectsUnapprovedHostsAndThirdPartyArticleBodies() {
        assertThatThrownBy(() -> policy.validate(admission("https://example.com/spring",
                KnowledgeSource.SPRING_OFFICIAL_DOCS, SourceLicense.OFFICIAL_DOCS), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");

        assertThatThrownBy(() -> policy.validate(admission("https://spring.io/blog/spring",
                KnowledgeSource.BAELDUNG, SourceLicense.CC_BY_SA), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body text");

        assertThatThrownBy(() -> policy.validate(new SourceAdmission(
                URI.create("https://docs.spring.io/spring-framework/reference/core.html"),
                KnowledgeSource.SPRING_OFFICIAL_DOCS,
                SourceLicense.OFFICIAL_DOCS,
                "Core container overview",
                "Spring Team",
                "core",
                "6.2",
                "https://www.apache.org/licenses/LICENSE-2.0",
                "approval-reference-only"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewer");
    }

    @Test
    void chunksRespectTokenLimitAndCarryOverlapAcrossParagraphs() {
        SourceAdmission source = admission("https://docs.spring.io/spring-framework/reference/core.html",
                KnowledgeSource.SPRING_OFFICIAL_DOCS, SourceLicense.OFFICIAL_DOCS);
        String text = IntStream.range(0, 1200)
                .mapToObj(index -> "word" + index + (index % 80 == 79 ? "\n\n" : " "))
                .collect(Collectors.joining());
        var document = new CrawledDocument(
                source.uri(), source.title(), "Core", text, null, null, "content-hash");

        var chunks = new ContextualChunker().chunk(document, source);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(512);
            assertThat(chunk.content()).contains("module: core");
        });
        for (int index = 1; index < chunks.size(); index++) {
            var previous = chunks.get(index - 1).sourceContent().split("\\s+");
            var current = chunks.get(index).sourceContent().split("\\s+");
            assertThat(current).startsWith(java.util.Arrays.copyOfRange(previous, previous.length - 64, previous.length));
        }
    }

    private SourceAdmission admission(String url, KnowledgeSource sourceType, SourceLicense license) {
        return new SourceAdmission(
                URI.create(url), sourceType, license, "Core container overview", "Spring Team", "core", "6.2",
                "https://www.apache.org/licenses/LICENSE-2.0", "test-reviewer", "test-approval-reference");
    }
}
