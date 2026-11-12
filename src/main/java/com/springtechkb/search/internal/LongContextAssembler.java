package com.springtechkb.search.internal;

import java.util.List;

import org.springframework.stereotype.Component;

import com.springtechkb.search.SearchHit;

/** Builds a bounded context window from ranked knowledge chunks for the CAG route. */
@Component
public class LongContextAssembler {

    private static final int MAX_CONTEXT_TOKENS = 12_000;

    public String assemble(List<SearchHit> hits) {
        StringBuilder context = new StringBuilder();
        int remainingCharacters = MAX_CONTEXT_TOKENS * 4;
        for (SearchHit hit : hits) {
            String block = "\n<untrusted_knowledge_chunk>\n[Source: " + escape(hit.sourceTitle())
                    + "; module: " + escape(hit.springModule())
                    + "; version: " + escape(hit.springVersion()) + "]\n"
                    + escape(hit.content()) + "\n</untrusted_knowledge_chunk>\n";
            if (block.length() > remainingCharacters) {
                if (remainingCharacters <= 0) break;
                context.append(block, 0, remainingCharacters);
                break;
            }
            context.append(block);
            remainingCharacters -= block.length();
        }
        return context.toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
