package com.springtechkb.search.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SpringVersionRange {

    private static final Pattern VERSION_PART = Pattern.compile("\\d+");
    private static final Pattern VALID_VERSION = Pattern.compile("\\d+(?:\\.\\d+)*(?:\\.x)?");

    private SpringVersionRange() {}

    static boolean includes(String version, String minimum, String maximum) {
        if (version == null || version.isBlank()) return true;
        if (!VALID_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid Spring version: " + version);
        }
        return (minimum == null || compare(version, minimum) >= 0)
                && (maximum == null || compare(version, maximum) <= 0);
    }

    static String sortKey(String version, boolean upperBound) {
        if (version == null || version.isBlank()) return null;
        if (!VALID_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid Spring version: " + version);
        }
        List<Integer> versionParts = parts(version);
        StringBuilder key = new StringBuilder();
        for (int index = 0; index < 3; index++) {
            if (index > 0) key.append('.');
            int segment = upperBound && version.endsWith(".x") && index == 2
                    ? 999_999
                    : index < versionParts.size() ? versionParts.get(index) : 0;
            key.append(String.format(Locale.ROOT, "%06d", segment));
        }
        return key.toString();
    }

    private static int compare(String left, String right) {
        List<Integer> leftParts = parts(left);
        List<Integer> rightParts = parts(right);
        int comparedParts = Math.min(leftParts.size(), rightParts.size());
        for (int i = 0; i < comparedParts; i++) {
            int compared = Integer.compare(leftParts.get(i), rightParts.get(i));
            if (compared != 0) return compared;
        }

        boolean leftWildcard = left.endsWith(".x");
        boolean rightWildcard = right.endsWith(".x");
        if (leftWildcard || rightWildcard) return 0;
        return Integer.compare(leftParts.size(), rightParts.size());
    }

    private static List<Integer> parts(String version) {
        Matcher matcher = VERSION_PART.matcher(version);
        List<Integer> parts = new ArrayList<>();
        while (matcher.find()) parts.add(Integer.parseInt(matcher.group()));
        return parts;
    }
}
