package com.springtechkb.assessment.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "springtechkb.assessment")
record AssessmentPhase3Properties(
        @DefaultValue Sandbox sandbox,
        @DefaultValue Quality quality,
        @DefaultValue Irt irt
) {
    AssessmentPhase3Properties() {
        this(new Sandbox(), new Quality(), new Irt());
    }

    record Sandbox(
            @DefaultValue("local") String strategy,
            @DefaultValue("5s") Duration compileTimeout,
            @DefaultValue("2s") Duration runTimeout,
            @DefaultValue("128") int maxMemoryMb,
            @DefaultValue("20000") int maxSourceChars
    ) {
        Sandbox() {
            this("local", Duration.ofSeconds(5), Duration.ofSeconds(2), 128, 20_000);
        }
    }

    record Quality(
            @DefaultValue BigScore minimumScore,
            @DefaultValue("120") int staleSourceDays
    ) {
        Quality() {
            this(new BigScore(), 120);
        }
    }

    record BigScore(
            @DefaultValue("65") int draft,
            @DefaultValue("75") int approval
    ) {
        BigScore() {
            this(65, 75);
        }
    }

    record Irt(@DefaultValue("0 */30 * * * *") String cron) {
        Irt() {
            this("0 */30 * * * *");
        }
    }
}
