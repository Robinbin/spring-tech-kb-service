package com.springtechkb.knowledge.internal;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import com.springtechkb.HashUtils;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.springtechkb.knowledge.CrawledDocument;
import com.springtechkb.knowledge.KnowledgeSource;
import com.springtechkb.knowledge.SourceAdmission;
import com.springtechkb.knowledge.SourceLicense;

/**
 * Robots-aware, conditional, rate-limited crawler limited to D1-approved Spring documentation.
 */
@Component
public class OfficialDocumentationCrawler {

    private static final String USER_AGENT = "SpringTechKnowledgeBot/1.0 (+https://spring.io)";
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_EXTRACTED_CHARACTERS = 12_000;
    private final SourceAdmissionPolicy admissionPolicy;
    private final Duration minimumDelay;
    private final Map<String, RobotsRules> robotsCache = new HashMap<>();
    private final Map<String, Long> lastRequestNanos = new HashMap<>();

    public OfficialDocumentationCrawler(
            SourceAdmissionPolicy admissionPolicy,
            @Value("${springtechkb.ingestion.crawl-delay:PT1S}") Duration minimumDelay) {
        this.admissionPolicy = admissionPolicy;
        this.minimumDelay = minimumDelay;
    }

    public CrawlResult crawl(SourceAdmission admission, String previousEtag, String previousLastModified) {
        admissionPolicy.validate(admission, true);
        URI uri = admission.uri().normalize();
        RobotsRules rules = robotsRules(uri);
        if (!rules.allows(uri.getPath())) {
            throw new IllegalArgumentException("Robots policy disallows crawling " + uri);
        }

        try {
            Connection.Response response = fetchPage(uri, rules.delay(), previousEtag, previousLastModified);
            if (response.statusCode() == 304) {
                return CrawlResult.unchanged();
            }
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected source response (HTTP " + response.statusCode() + ")");
            }
            Document document = response.parse();
            String text = boundedExcerpt(extractText(document));
            if (text.isBlank()) {
                throw new IOException("Approved source contains no extractable documentation text: " + uri);
            }
            return CrawlResult.updated(new CrawledDocument(
                    uri,
                    nonBlank(document.title(), admission.title()),
                    sectionPath(document),
                    text,
                    response.header("ETag"),
                    response.header("Last-Modified"),
                    HashUtils.sha256Hex(text)
            ));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to crawl approved source " + uri, exception);
        }
    }

    private Connection.Response fetchPage(
            URI uri, Duration robotsDelay, String previousEtag, String previousLastModified) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            pace(uri, robotsDelay);
            Connection connection = connection(uri.toString());
            if (previousEtag != null && !previousEtag.isBlank()) {
                connection.header("If-None-Match", previousEtag);
            }
            if (previousLastModified != null && !previousLastModified.isBlank()) {
                connection.header("If-Modified-Since", previousLastModified);
            }
            Connection.Response response = connection.execute();
            if (response.statusCode() != 429 && response.statusCode() < 500) return response;
            if (attempt == 2) {
                throw new IOException("Temporary source failure after retries (HTTP "
                        + response.statusCode() + ")");
            }
            sleep(retryDelay(response.header("Retry-After"), attempt));
        }
        throw new IllegalStateException("Retry loop exited unexpectedly");
    }

    private Duration retryDelay(String retryAfter, int attempt) {
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                return Duration.ofSeconds(Math.max(0, Math.min(seconds, 300)));
            } catch (NumberFormatException ignored) {
                try {
                    Instant retryAt = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant();
                    return Duration.ofSeconds(Math.max(0, Math.min(
                            Duration.between(Instant.now(), retryAt).toSeconds(), 300)));
                } catch (java.time.format.DateTimeParseException ignoredDate) {
                    // Invalid server hints fall back to bounded exponential backoff.
                }
            }
        }
        return Duration.ofSeconds(1L << attempt);
    }

    private void sleep(Duration delay) {
        try {
            TimeUnit.NANOSECONDS.sleep(delay.toNanos());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry documentation crawl", exception);
        }
    }

    private Connection connection(String url) {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout((int) Duration.ofSeconds(30).toMillis())
                .maxBodySize(MAX_RESPONSE_BYTES)
                .followRedirects(false)
                .ignoreHttpErrors(true)
                .ignoreContentType(false)
                .header("Accept", "text/html,application/xhtml+xml");
    }

    private RobotsRules robotsRules(URI pageUri) {
        String origin = pageUri.getScheme() + "://" + pageUri.getAuthority();
        synchronized (robotsCache) {
            RobotsRules cached = robotsCache.get(origin);
            if (cached != null) return cached;
        }
        pace(pageUri, minimumDelay);
        URI robotsUri = URI.create(origin + "/robots.txt");
        try {
            Connection.Response response = connection(robotsUri.toString()).execute();
            RobotsRules rules;
            if (response.statusCode() == 404) {
                rules = RobotsRules.allowAll(minimumDelay);
            } else if (response.statusCode() != 200) {
                throw new IOException("Cannot verify robots.txt (HTTP " + response.statusCode() + ")");
            } else {
                rules = RobotsRules.parse(response.body(), minimumDelay);
            }
            synchronized (robotsCache) {
                robotsCache.put(origin, rules);
            }
            return rules;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read robots.txt for " + origin, exception);
        }
    }

    private void pace(URI uri, Duration robotsDelay) {
        long delayNanos = Math.max(minimumDelay.toNanos(), robotsDelay.toNanos());
        String key = uri.getScheme() + "://" + uri.getAuthority();
        long waitNanos;
        synchronized (lastRequestNanos) {
            long now = System.nanoTime();
            long earliest = Math.max(now, lastRequestNanos.getOrDefault(key, now)) + delayNanos;
            waitNanos = Math.max(0L, earliest - now);
            lastRequestNanos.put(key, Math.max(now, earliest));
        }
        if (waitNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while applying crawl rate limit", exception);
            }
        }
    }

    private String extractText(Document document) {
        document.select("script, style, nav, header, footer, aside, form, noscript, svg, .breadcrumb, .pagination")
                .remove();
        Element content = document.selectFirst("main, article, [role=main]");
        if (content == null) content = document.body();
        if (content == null) return "";
        List<String> lines = new ArrayList<>();
        for (Element element : content.select("h1, h2, h3, h4, p, li, pre, blockquote, table")) {
            String text = element.text().trim();
            if (text.isBlank()) continue;
            if (element.tagName().matches("h[1-4]")) {
                lines.add("\n" + "#".repeat(Integer.parseInt(element.tagName().substring(1))) + " " + text);
            } else if (element.tagName().equals("li")) {
                lines.add("- " + text);
            } else {
                lines.add(text);
            }
        }
        if (lines.isEmpty()) return content.text().trim();
        return String.join("\n\n", lines).trim();
    }

    static String boundedExcerpt(String text) {
        if (text.length() <= MAX_EXTRACTED_CHARACTERS) return text;
        String marker = "\n\n[Excerpt truncated at the configured page limit.]";
        int end = MAX_EXTRACTED_CHARACTERS - marker.length();
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        int paragraphEnd = text.lastIndexOf("\n\n", end);
        if (paragraphEnd > MAX_EXTRACTED_CHARACTERS / 2) end = paragraphEnd;
        return text.substring(0, end).stripTrailing() + marker;
    }

    private String sectionPath(Document document) {
        Elements headings = document.select("main h1, main h2, main h3, article h1, article h2, article h3");
        return headings.eachText().stream().limit(8).reduce((left, right) -> left + " > " + right).orElse("");
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record CrawlResult(boolean notModified, CrawledDocument document) {
        static CrawlResult unchanged() {
            return new CrawlResult(true, null);
        }

        static CrawlResult updated(CrawledDocument document) {
            return new CrawlResult(false, document);
        }
    }

    private record RobotsRules(List<String> disallowed, List<String> allowed, Duration delay) {
        boolean allows(String path) {
            int allowedLength = allowed.stream().filter(path::startsWith).mapToInt(String::length).max().orElse(-1);
            int disallowedLength = disallowed.stream().filter(path::startsWith)
                    .mapToInt(String::length).max().orElse(-1);
            return allowedLength >= disallowedLength;
        }

        static RobotsRules allowAll(Duration delay) {
            return new RobotsRules(List.of(), List.of("/"), delay);
        }

        static RobotsRules parse(String content, Duration defaultDelay) {
            List<String> disallowed = new ArrayList<>();
            List<String> allowed = new ArrayList<>();
            Duration delay = defaultDelay;
            boolean applies = false;
            boolean matchedAgent = false;
            for (String line : content.split("\\R")) {
                String clean = line.split("#", 2)[0].trim();
                int separator = clean.indexOf(':');
                if (separator < 0) continue;
                String key = clean.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String value = clean.substring(separator + 1).trim();
                if (key.equals("user-agent")) {
                    if (matchedAgent) applies = false;
                    applies = value.equals("*") || USER_AGENT.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
                    matchedAgent |= applies;
                } else if (applies && key.equals("disallow") && !value.isBlank()) {
                    disallowed.add(value);
                } else if (applies && key.equals("allow") && !value.isBlank()) {
                    allowed.add(value);
                } else if (applies && key.equals("crawl-delay")) {
                    try {
                        delay = Duration.ofMillis((long) (Double.parseDouble(value) * 1000));
                    } catch (NumberFormatException exception) {
                        throw new IllegalArgumentException("Invalid robots crawl-delay: " + value, exception);
                    }
                }
            }
            return new RobotsRules(List.copyOf(disallowed), List.copyOf(allowed), delay);
        }
    }
}
