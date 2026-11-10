package com.springtechkb.search.internal;

import java.security.Principal;
import java.util.List;

import com.springtechkb.search.RagRequest;
import com.springtechkb.search.RagStreamEvent;
import com.springtechkb.search.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/** E1 search and E3 single-turn RAG endpoints. */
@RestController
@RequestMapping("/api")
class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final HybridSearchService searchService;
    private final RagAnswerService ragAnswerService;

    SearchController(HybridSearchService searchService, RagAnswerService ragAnswerService) {
        this.searchService = searchService;
        this.ragAnswerService = ragAnswerService;
    }

    @GetMapping("/search")
    SearchResponse search(
            @RequestParam("q") String query,
            @RequestParam(value = "module", required = false) String module,
            @RequestParam(value = "springVersion", required = false) String springVersion,
            @RequestParam(value = "topK", defaultValue = "20") int topK) {
        if (topK < 1 || topK > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "topK must be between 1 and 100");
        }
        return SearchResponse.from(searchService.search(query, module, springVersion, topK));
    }

    @PostMapping(value = "/chat/rag", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<RagStreamEvent>> chat(@RequestBody RagRequest request, Principal caller) {
        return Flux.defer(() -> ragAnswerService.stream(request, caller))
                .onErrorResume(exception -> {
                    log.error("RAG endpoint failed", exception);
                    return Flux.just(ServerSentEvent.<RagStreamEvent>builder(
                                    RagStreamEvent.error("The RAG request could not be completed."))
                            .event("error")
                            .build());
                });
    }
}
