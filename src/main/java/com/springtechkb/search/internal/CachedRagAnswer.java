package com.springtechkb.search.internal;

import java.util.List;

import com.springtechkb.search.RagCitation;

public record CachedRagAnswer(String answer, List<RagCitation> citations) {}
