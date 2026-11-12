package com.springtechkb.search.internal;

import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.springtechkb.search.SearchHit;

/** Shared state carried through the agentic retrieval graph. */
class KbAgentState extends AgentState {

    static final String QUESTION   = "question";
    static final String MODULE     = "module";
    static final String VERSION    = "version";
    static final String HOPS       = "hops";
    static final String HITS       = "hits";
    static final String ITERATIONS = "iterations";
    static final String ROUTE      = "route";

    KbAgentState(Map<String, Object> initData) {
        super(initData);
    }

    Optional<String> question()   { return value(QUESTION); }
    Optional<String> module()     { return value(MODULE); }
    Optional<String> version()    { return value(VERSION); }
    Optional<String> route()      { return value(ROUTE); }

    @SuppressWarnings("unchecked")
    Optional<List<String>> hops() { return value(HOPS); }

    @SuppressWarnings("unchecked")
    Optional<List<SearchHit>> hits() { return value(HITS); }

    Optional<Integer> iterations() { return value(ITERATIONS); }
}
