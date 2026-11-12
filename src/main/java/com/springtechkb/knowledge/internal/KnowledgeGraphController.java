package com.springtechkb.knowledge.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.springtechkb.knowledge.KnowledgeGraph;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge/topics")
class KnowledgeGraphController {
    private final KnowledgeGraph graph;
    KnowledgeGraphController(KnowledgeGraph graph) { this.graph = graph; }

    @GetMapping
    List<KnowledgeGraph.Topic> topics() { return graph.topics(); }

    @GetMapping("/{id}/prerequisites")
    List<KnowledgeGraph.Topic> prerequisites(@PathVariable UUID id) { return graph.prerequisites(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    Map<String, UUID> create(@Valid @RequestBody NewTopic body) {
        return Map.of("id", graph.createTopic(body.name(), body.springModule(), body.description()));
    }

    @PutMapping("/{id}/prerequisites/{prerequisiteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void add(@PathVariable UUID id, @PathVariable UUID prerequisiteId) { graph.addPrerequisite(id, prerequisiteId); }

    @DeleteMapping("/{id}/prerequisites/{prerequisiteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void remove(@PathVariable UUID id, @PathVariable UUID prerequisiteId) { graph.removePrerequisite(id, prerequisiteId); }

    record NewTopic(@NotBlank @Size(max = 255) String name, @Size(max = 100) String springModule,
                    @Size(max = 4000) String description) {}
}
