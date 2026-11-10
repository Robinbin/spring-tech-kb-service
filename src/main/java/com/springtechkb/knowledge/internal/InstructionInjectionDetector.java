package com.springtechkb.knowledge.internal;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Rejects common instruction-override payloads before source text enters the indexed corpus. */
@Component
public class InstructionInjectionDetector {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile(
                    "(?is)\\b(ignore|disregard|forget)\\s+(all\\s+)?(previous|prior|above|system|developer)\\s+(instructions?|prompts?)\\b"),
            Pattern.compile("(?is)\\b(reveal|print|expose)\\s+(the\\s+)?(system|developer)\\s+prompt\\b"),
            Pattern.compile("(?is)\\b(from now on|you are now)\\s+(an?\\s+)?(unrestricted|unfiltered|different)\\b"),
            Pattern.compile("(?is)<\\s*/?\\s*(system|developer|assistant)\\s*>"));

    public void assertSafe(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("Knowledge source content must not be blank");
        }
        if (INJECTION_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(sourceText).find())) {
            throw new UnsafeKnowledgeContentException(
                    "Knowledge source contains an instruction-override pattern and was rejected");
        }
    }

    public static final class UnsafeKnowledgeContentException extends IllegalArgumentException {
        public UnsafeKnowledgeContentException(String message) {
            super(message);
        }
    }
}
