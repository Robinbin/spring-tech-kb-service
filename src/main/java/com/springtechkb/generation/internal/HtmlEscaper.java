package com.springtechkb.generation.internal;

public final class HtmlEscaper {

    private HtmlEscaper() {}

    public static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
