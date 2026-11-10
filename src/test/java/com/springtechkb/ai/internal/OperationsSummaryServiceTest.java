package com.springtechkb.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class OperationsSummaryServiceTest {

    @Test
    void ratiosAreNullWithoutDenominatorsAndUseSixDecimalPlaces() {
        assertThat(OperationsSummaryService.ratio(0, 0)).isNull();
        assertThat(OperationsSummaryService.ratio(1, 0)).isNull();
        assertThat(OperationsSummaryService.ratio(25, 100)).isEqualByComparingTo(new BigDecimal("0.250000"));
        assertThat(OperationsSummaryService.cacheReadRatio(0, 0)).isNull();
        assertThat(OperationsSummaryService.cacheReadRatio(25, 75))
                .isEqualByComparingTo(new BigDecimal("0.250000"));
    }

    @Test
    void rejectsUnboundedDateWindowsBeforeQuerying() {
        var service = new OperationsSummaryService(mock(JdbcTemplate.class), CircuitBreakerRegistry.ofDefaults());

        assertThatThrownBy(() -> service.summary(31)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void operationsSummaryRouteIsAdminOnly() throws NoSuchMethodException {
        assertThat(OperationsSummaryController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/operations");
        var method = OperationsSummaryController.class.getDeclaredMethod("summary", int.class);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/summary");
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
    }
}
