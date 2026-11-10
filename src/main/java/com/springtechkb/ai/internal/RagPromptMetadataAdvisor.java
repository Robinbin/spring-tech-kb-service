package com.springtechkb.ai.internal;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Attaches the stable prompt namespace to each Spring AI request for trace and cache attribution. */
@Component
class RagPromptMetadataAdvisor implements BaseAdvisor {

    static final String PROMPT_NAMESPACE_CONTEXT_KEY = "springtechkb.rag.prompt-namespace";

    private final String promptVersion;

    RagPromptMetadataAdvisor(@Value("${springtechkb.rag.prompt-version:v1}") String promptVersion) {
        this.promptVersion = promptVersion;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        Object namespace = request.context().get(PROMPT_NAMESPACE_CONTEXT_KEY);
        return request.mutate()
                .context(PROMPT_NAMESPACE_CONTEXT_KEY, namespace == null ? promptVersion : namespace)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
