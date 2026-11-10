package com.springtechkb.ai;

public interface StructuredGenerationGateway {
    <T> Generated<T> generate(String promptName, String callerKey, String input, Class<T> outputType);

    record Generated<T>(T value, String modelId, String promptVersion) {}
}
