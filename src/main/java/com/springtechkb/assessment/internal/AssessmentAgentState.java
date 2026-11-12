package com.springtechkb.assessment.internal;

import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** State carried through the assessment sub-graph (question generation → auto-grading). */
class AssessmentAgentState extends AgentState {

    static final String ADMIN_ID        = "admin_id";
    static final String TOPIC_IDS       = "topic_ids";       // List<String> (UUIDs as strings)
    static final String QUESTION_TYPES  = "question_types";  // List<String>
    static final String COUNT_PER_TOPIC = "count_per_topic"; // Integer
    static final String BATCH_ID        = "batch_id";        // String (UUID)
    static final String GENERATED_COUNT = "generated_count"; // Integer
    static final String QUALITY_PASSED  = "quality_passed";  // Integer

    AssessmentAgentState(Map<String, Object> initData) {
        super(initData);
    }

    Optional<String> adminId()         { return value(ADMIN_ID); }
    Optional<Integer> countPerTopic()  { return value(COUNT_PER_TOPIC); }
    Optional<String> batchId()         { return value(BATCH_ID); }

    @SuppressWarnings("unchecked")
    Optional<List<String>> topicIds()       { return value(TOPIC_IDS); }

    @SuppressWarnings("unchecked")
    Optional<List<String>> questionTypes()  { return value(QUESTION_TYPES); }
}
