package com.springtechkb.knowledge;

import java.util.UUID;

/** Signals that a source and its lexical/vector projections were committed together. */
public record KnowledgeIndexedEvent(UUID outboxId, UUID sourceId) {}
