package com.springtechkb.search.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;

import com.springtechkb.ai.GenerationTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PromptDiffTest {
    @Test
    void providesExactChangedRegionAndChecksumProvenance() {
        var registry = spy(new PromptRegistryService(mock(JdbcTemplate.class), "v1"));
        doReturn(List.of(version(1, "shared\nold\nsuffix"), version(2, "shared\nnew\nsuffix")))
                .when(registry).versions("learning-plan");
        var diff = registry.diff("learning-plan", 1, 2);
        assertThat(diff.startLine()).isEqualTo(2);
        assertThat(diff.removedLines()).containsExactly("old");
        assertThat(diff.addedLines()).containsExactly("new");
        assertThat(diff.fromChecksum()).isNotEqualTo(diff.toChecksum());
        assertThatThrownBy(() -> registry.diff("learning-plan", 1, 3)).hasMessageContaining("404");
    }

    @Test
    void preservesTrailingNewlineChangesRatherThanReportingAnEmptyDiff() {
        var registry = spy(new PromptRegistryService(mock(JdbcTemplate.class), "v1"));
        doReturn(List.of(version(1, "instruction"), version(2, "instruction\n")))
                .when(registry).versions("learning-plan");
        var diff = registry.diff("learning-plan", 1, 2);
        assertThat(diff.startLine()).isEqualTo(2);
        assertThat(diff.removedLines()).isEmpty();
        assertThat(diff.addedLines()).containsExactly("");
    }

    private static PromptRegistryService.PromptVersion version(int number, String text) {
        return new PromptRegistryService.PromptVersion("learning-plan", number, text, "STANDARD", "",
                GenerationTelemetry.checksum(text), number == 1, 100, Instant.EPOCH);
    }
}
