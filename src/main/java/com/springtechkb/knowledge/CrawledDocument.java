package com.springtechkb.knowledge;

import java.net.URI;

/** Cleaned, conditionally-fetched documentation page. */
public record CrawledDocument(
        URI uri,
        String title,
        String sectionPath,
        String text,
        String etag,
        String lastModified,
        String contentHash
) {}
