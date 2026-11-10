package com.springtechkb.knowledge;

import java.util.List;
import java.util.UUID;

public interface KnowledgeGraph {
    List<Topic> topics();
    List<Topic> prerequisites(UUID topicId);
    List<Topic> orderedClosure(List<UUID> topicIds);

    UUID createTopic(String name, String module, String description);
    void addPrerequisite(UUID topicId, UUID prerequisiteId);
    void removePrerequisite(UUID topicId, UUID prerequisiteId);

    /** Returns approved knowledge source IDs that have chunks tagged with the given topic. */
    List<UUID> approvedSourceIds(UUID topicId);

    record Topic(UUID id, String name, String springModule, String description, List<UUID> prerequisites) {}
}
