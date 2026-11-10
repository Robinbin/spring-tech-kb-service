package com.springtechkb.search.internal;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.springtechkb.search.ClassifiedQuery;
import com.springtechkb.search.QueryRoute;
import com.springtechkb.search.SearchIntent;

/** Low-latency deterministic classifier; it avoids an LLM call on the ordinary search path. */
@Component
public class QueryIntentClassifier {

    private static final Pattern VERSION = Pattern.compile("\\b\\d+\\.\\d+(?:\\.\\d+)?(?:\\.x)?\\b");

    private final List<IntentClassificationRule> rules;

    QueryIntentClassifier() {
        this.rules = List.of(
                new PersonalizedRule(),
                new VersionComparisonRule(),
                new LongContextRule(),
                new FallbackRule());
    }

    public ClassifiedQuery classify(String question, String requestedDomain) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        String domain = requestedDomain == null || requestedDomain.isBlank()
                ? inferDomain(normalized) : requestedDomain.trim().toLowerCase(Locale.ROOT);
        return rules.stream()
                .flatMap(rule -> rule.classify(normalized, question, domain).stream())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No classification rule matched"));
    }

    static boolean containsExplicitVersions(String question) {
        return VERSION.matcher(question).find();
    }

    private static String inferDomain(String question) {
        for (String domain : new String[]{"security", "data", "mvc", "webflux", "aop", "core", "boot"}) {
            if (question.contains(domain)) return domain;
        }
        if (question.contains("安全")) return "security";
        if (question.contains("数据")) return "data";
        if (question.contains("控制器") || question.contains("mvc")) return "mvc";
        return "spring-general";
    }

    private static class PersonalizedRule implements IntentClassificationRule {
        private static final Pattern PATTERN = Pattern.compile(
                "\\b(my|for me|personalized|tailored|learning plan|study plan|grade|mark|subjective answer)\\b|"
                        + "我的|为我|学习计划|批改|主观题|能力报告|技能评估|个性化");

        @Override
        public Optional<ClassifiedQuery> classify(String normalized, String original, String domain) {
            if (PATTERN.matcher(normalized).find()) {
                return Optional.of(new ClassifiedQuery(SearchIntent.PERSONALIZED_TASK, QueryRoute.FAST_PATH, domain));
            }
            return Optional.empty();
        }
    }

    private static class VersionComparisonRule implements IntentClassificationRule {
        private static final Pattern PATTERN = Pattern.compile(
                "\\b(compare|comparison|difference|versus|vs|migration)\\b|对比|区别|差异|迁移");

        @Override
        public Optional<ClassifiedQuery> classify(String normalized, String original, String domain) {
            if (PATTERN.matcher(normalized).find()) {
                return Optional.of(new ClassifiedQuery(SearchIntent.VERSION_COMPARISON, QueryRoute.AGENTIC, domain));
            }
            return Optional.empty();
        }
    }

    private static class LongContextRule implements IntentClassificationRule {
        private static final Pattern PATTERN = Pattern.compile(
                "\\b(comprehensive|in detail|summarize|overview|all aspects)\\b|全面|详细总结|整体概述");

        @Override
        public Optional<ClassifiedQuery> classify(String normalized, String original, String domain) {
            if (original.length() >= 500 || PATTERN.matcher(normalized).find()) {
                return Optional.of(new ClassifiedQuery(SearchIntent.LONG_CONTEXT, QueryRoute.CAG, domain));
            }
            return Optional.empty();
        }
    }

    private static class FallbackRule implements IntentClassificationRule {
        @Override
        public Optional<ClassifiedQuery> classify(String normalized, String original, String domain) {
            return Optional.of(new ClassifiedQuery(SearchIntent.KNOWLEDGE_QA, QueryRoute.FAST_PATH, domain));
        }
    }
}
