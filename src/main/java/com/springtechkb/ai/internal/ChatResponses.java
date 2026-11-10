package com.springtechkb.ai.internal;

import org.springframework.ai.chat.model.ChatResponse;

public final class ChatResponses {

    private ChatResponses() {}

    public static String safeText(ChatResponse response) {
        if (response == null) return "";
        var result = response.getResult();
        if (result == null) return "";
        var output = result.getOutput();
        if (output == null) return "";
        String text = output.getText();
        return text != null ? text : "";
    }
}
