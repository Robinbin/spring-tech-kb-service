package com.springtechkb.ai;

public interface PromptCatalog {
    Prompt resolvePrompt(String name, String callerKey);

    record Prompt(String name, String version, String content) {
        public String namespace() { return name + ":v" + version.replaceFirst("^v", ""); }
    }
}
