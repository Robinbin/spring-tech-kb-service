package com.springtechkb.generation.internal;

import com.springtechkb.search.SearchHit;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** State carried through the generation sub-graph (retrieval → generation). */
class GenerationAgentState extends AgentState {

    static final String REQUEST_TYPE   = "request_type";   // "example" | "compare" | "quiz"
    static final String QUERY          = "query";
    static final String MODULE         = "module";
    static final String SPRING_VERSION = "spring_version";
    static final String OWNER_ID       = "owner_id";
    static final String KNOWLEDGE_HITS = "knowledge_hits";
    static final String OUTPUT_ID      = "output_id";      // UUID of persisted Output

    GenerationAgentState(Map<String, Object> initData) {
        super(initData);
    }

    Optional<String> requestType()   { return value(REQUEST_TYPE); }
    Optional<String> query()         { return value(QUERY); }
    Optional<String> module()        { return value(MODULE); }
    Optional<String> springVersion() { return value(SPRING_VERSION); }
    Optional<String> ownerId()       { return value(OWNER_ID); }
    Optional<String> outputId()      { return value(OUTPUT_ID); }

    @SuppressWarnings("unchecked")
    Optional<List<SearchHit>> knowledgeHits() { return value(KNOWLEDGE_HITS); }
}
