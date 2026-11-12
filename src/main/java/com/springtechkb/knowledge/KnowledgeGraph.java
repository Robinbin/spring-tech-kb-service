package com.springtechkb.knowledge;

import java.util.List;
import java.util.UUID;

public interface KnowledgeGraph {
    List<Topic> topics();
    List<Topic> prerequisites(UUID topicId);
    List<Topic> orderedClosure(List<UUID> topicIds);

    default UUID createTopic(String name, String module, String description) { throw new UnsupportedOperationException(); }
    default void addPrerequisite(UUID topicId, UUID prerequisiteId) { throw new UnsupportedOperationException(); }
    default void removePrerequisite(UUID topicId, UUID prerequisiteId) { throw new UnsupportedOperationException(); }

    /** Returns approved knowledge source IDs that have chunks tagged with the given topic. */
    default List<UUID> approvedSourceIds(UUID topicId) { return List.of(); }

    record Topic(UUID id, String name, String springModule, String description, List<UUID> prerequisites) {}
}
