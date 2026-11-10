package com.springtechkb.ai.internal;

import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputConfig;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit tier models; Spring AI's model auto-configuration is intentionally disabled. */
@Configuration
class AiModelConfiguration {

    @Bean("liteChatModel")
    ChatModel liteChatModel(
            @Value("${spring.ai.anthropic.api-key}") String apiKey,
            @Value("${springtechkb.ai.models.lite}") String model,
            @Value("${springtechkb.ai.models.lite-effort}") String effort,
            @Value("${spring.ai.anthropic.chat.options.max-tokens:4096}") int maxTokens) {
        return createModel(apiKey, model, effort, maxTokens);
    }

    @Bean("standardChatModel")
    ChatModel standardChatModel(
            @Value("${spring.ai.anthropic.api-key}") String apiKey,
            @Value("${springtechkb.ai.models.standard}") String model,
            @Value("${springtechkb.ai.models.standard-effort}") String effort,
            @Value("${spring.ai.anthropic.chat.options.max-tokens:4096}") int maxTokens) {
        return createModel(apiKey, model, effort, maxTokens);
    }

    @Bean("expertChatModel")
    ChatModel expertChatModel(
            @Value("${spring.ai.anthropic.api-key}") String apiKey,
            @Value("${springtechkb.ai.models.expert}") String model,
            @Value("${springtechkb.ai.models.expert-effort}") String effort,
            @Value("${spring.ai.anthropic.chat.options.max-tokens:4096}") int maxTokens) {
        return createModel(apiKey, model, effort, maxTokens);
    }

    private ChatModel createModel(String apiKey, String model, String effort, int maxTokens) {
        if (maxTokens < 1) throw new IllegalArgumentException("Anthropic max tokens must be positive");
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .apiKey(apiKey)
                .model(Model.of(model))
                .effort(OutputConfig.Effort.of(effort))
                .maxTokens(maxTokens)
                .build();
        return AnthropicChatModel.builder().options(options).build();
    }
}
