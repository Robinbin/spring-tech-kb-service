package com.springtechkb.search.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.security.Principal;

import com.springtechkb.search.ClassifiedQuery;
import com.springtechkb.search.QueryRoute;
import com.springtechkb.search.RagCitation;
import com.springtechkb.search.RagRequest;
import com.springtechkb.search.RagStreamEvent;
import com.springtechkb.search.SearchHit;
import com.springtechkb.search.RerankedSearchService;
import com.springtechkb.ai.ChatTier;
import com.springtechkb.ai.LlmCallRecorder;
import com.springtechkb.ai.TokenQuotaService;
import com.springtechkb.ai.RagChatGateway;
import com.springtechkb.ai.GenerationTelemetry;
import com.springtechkb.ai.PromptCatalog;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** E3 single-turn RAG streaming API orchestration. */
@Service
public class RagAnswerService {

    private final RagChatGateway chatGateway;
    private final QueryIntentClassifier classifier;
    private final RerankedSearchService rerankedSearch;
    private final HybridSearchService hybridSearch;
    private final AgenticRetrievalService agenticRetrieval;
    private final PromptPrefixRenderer promptRenderer;
    private final PromptRegistryService promptRegistry;
    private final SemanticAnswerCache answerCache;
    private final TokenQuotaService tokenQuota;
    private final LlmCallRecorder callRecorder;
    private final MeterRegistry meters;
    private final GenerationTelemetry telemetry;

    RagAnswerService(
            RagChatGateway chatGateway,
            QueryIntentClassifier classifier,
            RerankedSearchService rerankedSearch,
            HybridSearchService hybridSearch,
            AgenticRetrievalService agenticRetrieval,
            PromptPrefixRenderer promptRenderer,
            PromptRegistryService promptRegistry,
            SemanticAnswerCache answerCache,
            TokenQuotaService tokenQuota,
            LlmCallRecorder callRecorder,
            MeterRegistry meters,
            GenerationTelemetry telemetry) {
        this.chatGateway = chatGateway;
        this.classifier = classifier;
        this.rerankedSearch = rerankedSearch;
        this.hybridSearch = hybridSearch;
        this.agenticRetrieval = agenticRetrieval;
        this.promptRenderer = promptRenderer;
        this.promptRegistry = promptRegistry;
        this.answerCache = answerCache;
        this.tokenQuota = tokenQuota;
        this.callRecorder = callRecorder;
        this.meters = meters;
        this.telemetry = telemetry;
    }

    public Flux<ServerSentEvent<RagStreamEvent>> stream(RagRequest request) {
        return stream(request, null);
    }

    public Flux<ServerSentEvent<RagStreamEvent>> stream(RagRequest request, Principal caller) {
        return Flux.defer(() -> {
            String callerKey = caller == null ? "anonymous" : caller.getName();
            String cohortKey = caller == null ? UUID.randomUUID().toString() : callerKey;
            PromptRegistryService.ResolvedPrompt prompt = promptRegistry.resolve(cohortKey);
            return telemetry.observeFlux("rag.answer", new PromptCatalog.Prompt(
                    prompt.name(), prompt.version(), prompt.content()), () -> streamResolved(request, callerKey, prompt));
        });
    }

    private Flux<ServerSentEvent<RagStreamEvent>> streamResolved(
            RagRequest request, String callerKey, PromptRegistryService.ResolvedPrompt prompt) {
        ClassifiedQuery classified = classifier.classify(request.question(), request.springModule());
        meters.counter("rag.requests", "route", classified.route().name()).increment();
        Timer.Sample ttft = Timer.start(meters);

        if (classified.shouldCache()) {
            var cached = answerCache.find(
                    request.question(), classified, request.springVersion(), request.springModule(),
                    prompt.namespace());
            if (cached.isPresent()) {
                cacheCounter("hit", prompt.namespace());
                return cachedStream(cached.get(), classified.route(), ttft);
            }
            cacheCounter("miss", prompt.namespace());
        } else {
            cacheCounter("bypass", prompt.namespace());
        }

        ChatTier requestedTier = classified.recommendedTier();
        ChatTier tier = tokenQuota.selectTier(requestedTier, callerKey);
        meters.counter("llm.requests", "tier", tier.name()).increment();
        List<SearchHit> hits = telemetry.inSpan("rag.retrieve", () -> retrieve(request, classified));
        List<RagCitation> citations = citations(hits);
        String systemPrefix = promptRenderer.render(hits, prompt);
        return modelStream(
                request.question(), systemPrefix, citations, classified, request, ttft,
                callerKey, tier, prompt.namespace());
    }

    private List<SearchHit> retrieve(RagRequest request, ClassifiedQuery classified) {
        List<SearchHit> hits;
        if (classified.route() == QueryRoute.AGENTIC) {
            AgenticRetrievalService.RetrievalResult result =
                    agenticRetrieval.retrieve(
                            request.question(), request.springModule(), request.springVersion());
            meters.counter("rag.agentic.iterations").increment(result.iterations());
            hits = rerankedSearch.rerank(request.question(), result.hits(), 5);
        } else if (classified.route() == QueryRoute.CAG) {
            List<SearchHit> candidates = hybridSearch.search(
                    request.question(), request.springModule(), request.springVersion(), 50);
            hits = rerankedSearch.rerank(request.question(), candidates, 20);
        } else {
            hits = rerankedSearch.search(request.question(), request.springModule(), request.springVersion());
        }
        return List.copyOf(hits);
    }

    private Flux<ServerSentEvent<RagStreamEvent>> modelStream(
            String question,
            String systemPrefix,
            List<RagCitation> citations,
            ClassifiedQuery classified,
            RagRequest request,
            Timer.Sample ttft,
            String callerKey,
            ChatTier tier,
            String promptNamespace) {
        StringBuilder assembledAnswer = new StringBuilder();
        AtomicBoolean firstToken = new AtomicBoolean();
        AtomicReference<RagChatGateway.ChatChunk> latestUsage = new AtomicReference<>();
        AtomicReference<Throwable> providerFailure = new AtomicReference<>();
        AtomicReference<Long> ttftMillis = new AtomicReference<>();
        long callStartedNanos = System.nanoTime();
        Span modelSpan = telemetry.startSpan("gen_ai.rag");
        modelSpan.setAttribute("langfuse.observation.type", "generation");
        Context modelContext = Context.current().with(modelSpan);
        Flux<ServerSentEvent<RagStreamEvent>> citationEvents = Flux.fromIterable(citations)
                .map(citation -> event(RagStreamEvent.citation(citation)));

        Flux<ServerSentEvent<RagStreamEvent>> tokenEvents = Flux.defer(() -> {
                    try (Scope ignored = modelContext.makeCurrent()) {
                        return chatGateway.stream(tier, systemPrefix, question, promptNamespace);
                    }
                })
                .concatMap(response -> {
                    if (response.inputTokens() != null || response.outputTokens() != null
                            || response.cacheReadTokens() != null) {
                        latestUsage.updateAndGet(previous -> mergeUsage(previous, response));
                    }
                    String text = response.text();
                    if (text == null || text.isEmpty()) return Mono.empty();
                    if (firstToken.compareAndSet(false, true)) {
                        long elapsed = System.nanoTime() - callStartedNanos;
                        ttftMillis.set(elapsed / 1_000_000L);
                        ttft.stop(Timer.builder("rag.time_to_first_token").register(meters));
                    }
                    assembledAnswer.append(text);
                    return Mono.just(event(RagStreamEvent.token(text)));
                })
                .publishOn(Schedulers.boundedElastic())
                .onErrorResume(exception -> {
                    providerFailure.set(exception);
                    modelSpan.setStatus(StatusCode.ERROR, exception.getClass().getSimpleName());
                    meters.counter("llm.requests", "result", "retrieval_fallback").increment();
                    if (firstToken.compareAndSet(false, true)) {
                        ttft.stop(Timer.builder("rag.time_to_first_token").register(meters));
                    }
                    return Mono.just(event(RagStreamEvent.token(retrievalFallbackText())));
                });

        return citationEvents.concatWith(tokenEvents)
                .concatWith(Mono.fromSupplier(() -> {
                    try (Scope ignored = modelContext.makeCurrent()) {
                        RagChatGateway.ChatChunk usage = latestUsage.get();
                        recordUsage(usage);
                        tokenQuota.recordUsage(
                                callerKey,
                                usage == null || usage.inputTokens() == null ? 0 : usage.inputTokens(),
                                usage == null || usage.outputTokens() == null ? 0 : usage.outputTokens());
                        callRecorder.record(
                                callerKey,
                                usage == null || usage.modelId() == null
                                        ? chatGateway.modelId(tier) : usage.modelId(),
                                tier,
                                usage == null ? null : usage.inputTokens(),
                                usage == null ? null : usage.outputTokens(),
                                usage == null ? null : usage.cacheReadTokens(),
                                ttftMillis.get(),
                                (System.nanoTime() - callStartedNanos) / 1_000_000L,
                                providerFailure.get() != null);
                        String completedRoute = providerFailure.get() == null
                                ? classified.route().name() : "RETRIEVAL_ONLY";
                        return event(RagStreamEvent.done(completedRoute, false));
                    }
                }))
                .doOnComplete(() -> {
                    if (classified.shouldCache() && providerFailure.get() == null) {
                        answerCache.put(question, assembledAnswer.toString(), citations,
                                classified, request.springVersion(), request.springModule(), promptNamespace);
                    }
                })
                .doFinally(signal -> modelSpan.end());
    }

    private Flux<ServerSentEvent<RagStreamEvent>> cachedStream(
            CachedRagAnswer answer, QueryRoute route, Timer.Sample ttft) {
        List<ServerSentEvent<RagStreamEvent>> events = new ArrayList<>();
        answer.citations().forEach(citation -> events.add(event(RagStreamEvent.citation(citation))));
        events.add(event(RagStreamEvent.token(answer.answer())));
        events.add(event(RagStreamEvent.done(route.name(), true)));
        AtomicBoolean firstToken = new AtomicBoolean();
        return Flux.fromIterable(events)
                .doOnNext(event -> {
                    if ("token".equals(event.data().type()) && firstToken.compareAndSet(false, true)) {
                        ttft.stop(Timer.builder("rag.time_to_first_token").register(meters));
                    }
                });
    }

    private void recordUsage(RagChatGateway.ChatChunk usage) {
        if (usage == null) return;
        incrementTokenCounter("input", usage.inputTokens());
        incrementTokenCounter("output", usage.outputTokens());
        Long cacheReadTokens = usage.cacheReadTokens();
        if (cacheReadTokens != null && cacheReadTokens > 0) {
            Counter.builder("llm.tokens")
                    .tag("type", "cache_read")
                    .register(meters)
                    .increment(cacheReadTokens);
        }
    }

    private static RagChatGateway.ChatChunk mergeUsage(
            RagChatGateway.ChatChunk previous, RagChatGateway.ChatChunk current) {
        if (previous == null) return current;
        return new RagChatGateway.ChatChunk(
                null,
                current.inputTokens() == null ? previous.inputTokens() : current.inputTokens(),
                current.outputTokens() == null ? previous.outputTokens() : current.outputTokens(),
                current.cacheReadTokens() == null ? previous.cacheReadTokens() : current.cacheReadTokens(),
                current.modelId() == null ? previous.modelId() : current.modelId(),
                current.tier() == null ? previous.tier() : current.tier());
    }

    private void incrementTokenCounter(String type, Integer tokens) {
        if (tokens != null && tokens > 0) {
            Counter.builder("llm.tokens").tag("type", type).register(meters).increment(tokens);
        }
    }

    private void cacheCounter(String result, String promptNamespace) {
        meters.counter(
                "rag.semantic_cache",
                "result", result,
                "prompt_version", promptNamespace).increment();
    }

    private List<RagCitation> citations(List<SearchHit> hits) {
        return hits.stream()
                .map(hit -> new RagCitation(
                        hit.id(), hit.sourceTitle(), hit.sourceUrl(), hit.springVersion(),
                        hit.springModule(), hit.sourceLicense()))
                .toList();
    }

    private String retrievalFallbackText() {
        return "The language model is temporarily unavailable. Review the retrieved citations above for the relevant source material.";
    }

    private static ServerSentEvent<RagStreamEvent> event(RagStreamEvent data) {
        return ServerSentEvent.<RagStreamEvent>builder(data).event(data.type()).build();
    }
}
