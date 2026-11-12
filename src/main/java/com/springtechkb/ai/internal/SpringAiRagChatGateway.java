package com.springtechkb.ai.internal;

import java.util.EnumMap;
import java.util.Map;

import com.springtechkb.ai.ChatTier;
import com.springtechkb.ai.RagChatGateway;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Anthropic provider adapter backed by one explicitly configured model per tier. */
@Component("providerRagChatGateway")
class SpringAiRagChatGateway implements RagChatGateway {

    private final Map<ChatTier, ChatClient> clients = new EnumMap<>(ChatTier.class);
    private final Map<ChatTier, String> modelIds = new EnumMap<>(ChatTier.class);

    SpringAiRagChatGateway(
            @Qualifier("liteChatModel") ChatModel lite,
            @Qualifier("standardChatModel") ChatModel standard,
            @Qualifier("expertChatModel") ChatModel expert,
            RagPromptMetadataAdvisor promptMetadataAdvisor,
            @Value("${springtechkb.ai.models.lite}") String liteModel,
            @Value("${springtechkb.ai.models.standard}") String standardModel,
            @Value("${springtechkb.ai.models.expert}") String expertModel) {
        clients.put(ChatTier.LITE, ChatClient.builder(lite).defaultAdvisors(promptMetadataAdvisor).build());
        clients.put(ChatTier.STANDARD, ChatClient.builder(standard).defaultAdvisors(promptMetadataAdvisor).build());
        clients.put(ChatTier.EXPERT, ChatClient.builder(expert).defaultAdvisors(promptMetadataAdvisor).build());
        modelIds.put(ChatTier.LITE, liteModel);
        modelIds.put(ChatTier.STANDARD, standardModel);
        modelIds.put(ChatTier.EXPERT, expertModel);
    }

    @Override
    public Flux<ChatChunk> stream(String stableSystemPrefix, String userQuestion) {
        return stream(ChatTier.STANDARD, stableSystemPrefix, userQuestion);
    }

    @Override
    public String modelId(ChatTier tier) {
        return modelIds.get(tier);
    }

    @Override
    public Flux<ChatChunk> stream(ChatTier tier, String stableSystemPrefix, String userQuestion) {
        return stream(tier, stableSystemPrefix, userQuestion, "v1");
    }

    @Override
    public Flux<ChatChunk> stream(
            ChatTier tier, String stableSystemPrefix, String userQuestion, String promptNamespace) {
        return clients.get(tier).prompt()
                .system(stableSystemPrefix)
                .user(userQuestion)
                .advisors(advisorSpec -> advisorSpec.param(
                        RagPromptMetadataAdvisor.PROMPT_NAMESPACE_CONTEXT_KEY, promptNamespace))
                .options(AnthropicChatOptions.builder()
                        .cacheOptions(AnthropicCacheOptions.builder()
                                .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                                .build()))
                .stream()
                .chatResponse()
                .map(response -> chunk(response, tier));
    }

    private ChatChunk chunk(ChatResponse response, ChatTier tier) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        String text = response.getResult() == null
                ? null : response.getResult().getOutput().getText();
        return new ChatChunk(
                text,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getCacheReadInputTokens(),
                modelIds.get(tier),
                tier);
    }
}
