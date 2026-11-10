package com.springtechkb.knowledge;

import java.net.URI;

/** A source proposal plus the licence evidence required before ingestion. */
public record SourceAdmission(
        URI uri,
        KnowledgeSource sourceType,
        SourceLicense license,
        String title,
        String author,
        String springModule,
        String version,
        String licenseUrl,
        String approvedBy,
        String approvalReference,
        String javaClassName
) {
    public SourceAdmission(
            URI uri,
            KnowledgeSource sourceType,
            SourceLicense license,
            String title,
            String author,
            String springModule,
            String version,
            String licenseUrl,
            String approvalReference) {
        this(uri, sourceType, license, title, author, springModule, version, licenseUrl, null, approvalReference, null);
    }

    public SourceAdmission(
            URI uri,
            KnowledgeSource sourceType,
            SourceLicense license,
            String title,
            String author,
            String springModule,
            String version,
            String licenseUrl,
            String approvedBy,
            String approvalReference) {
        this(uri, sourceType, license, title, author, springModule, version, licenseUrl, approvedBy,
                approvalReference, null);
    }

    public SourceAdmission {
        if (uri == null) throw new IllegalArgumentException("Source URI is required");
        if (sourceType == null) throw new IllegalArgumentException("Source type is required");
        if (license == null) throw new IllegalArgumentException("Source licence is required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Source title is required");
        if (javaClassName != null && !javaClassName.matches(
                "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            throw new IllegalArgumentException("Java class name must be a qualified Java identifier");
        }
    }
}
