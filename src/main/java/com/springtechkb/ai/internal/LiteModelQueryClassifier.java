package com.springtechkb.ai.internal;

import com.springtechkb.ai.QueryClassificationGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/** Lite-model classification — fast, cheap, one-shot. */
@Component
class LiteModelQueryClassifier implements QueryClassificationGateway {

    private final ChatClient client;

    LiteModelQueryClassifier(@Qualifier("liteChatModel") ChatModel liteModel) {
        this.client = ChatClient.builder(liteModel).build();
    }

    @Override
    public String classify(String question, String... candidates) {
        String options = String.join(", ", candidates).toUpperCase(Locale.ROOT);
        String prompt = """
                Classify the following Spring question. Reply with ONLY ONE word from: %s
                Question: %s""".formatted(options, question);

        String raw = client.prompt()
                .user(prompt)
                .call()
                .content();

        if (raw == null) return candidates[0];
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(candidates)
                .filter(c -> normalized.contains(c.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(candidates[0]);
    }
}
