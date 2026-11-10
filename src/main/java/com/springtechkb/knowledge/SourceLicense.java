package com.springtechkb.knowledge;

/** SPDX-oriented licence identifiers accepted by the knowledge ingestion pipeline. */
public enum SourceLicense {
    APACHE_2("APACHE_2"),
    CC_BY("CC_BY"),
    CC_BY_SA("CC_BY_SA"),
    OFFICIAL_DOCS("OFFICIAL_DOCS");

    private final String databaseValue;

    SourceLicense(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }
}
