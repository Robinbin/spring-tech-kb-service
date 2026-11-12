package com.springtechkb.assessment.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class SandboxPolicyGuard {
    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("\\bRuntime\\s*\\.\\s*getRuntime\\s*\\("),
            Pattern.compile("\\bProcessBuilder\\b"),
            Pattern.compile("\\bSystem\\s*\\.\\s*exit\\s*\\("),
            Pattern.compile("\\bjava\\.io\\.(File|FileWriter|FileOutputStream|RandomAccessFile)\\b"),
            Pattern.compile("\\bjava\\.nio\\.file\\.(Files|Path|Paths)\\b"),
            Pattern.compile("\\bjava\\.net\\.(Socket|ServerSocket|URL|URI|HttpURLConnection)\\b"),
            Pattern.compile("\\bClass\\s*\\.\\s*forName\\s*\\("),
            Pattern.compile("\\bgetDeclared(Field|Method|Constructor)s?\\s*\\("),
            Pattern.compile("\\bsetAccessible\\s*\\("),
            Pattern.compile("\\bjavax\\.script\\b"),
            Pattern.compile("\\bThread\\s*\\.\\s*sleep\\s*\\("),
            Pattern.compile("\\bnew\\s+Thread\\b"),
            Pattern.compile("\\bForkJoinPool\\b"),
            Pattern.compile("\\bExecutorService\\b"));

    private SandboxPolicyGuard() {}

    static List<String> validate(String sourceCode) {
        List<String> violations = new ArrayList<>();
        String safe = sourceCode == null ? "" : sourceCode;
        for (Pattern pattern : BLOCKED_PATTERNS) {
            if (pattern.matcher(safe).find()) {
                violations.add("Blocked by sandbox policy: " + pattern.pattern());
            }
        }
        return List.copyOf(violations);
    }
}
