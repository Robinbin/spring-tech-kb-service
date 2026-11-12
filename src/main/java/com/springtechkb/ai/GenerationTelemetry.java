package com.springtechkb.ai;

import java.util.function.Supplier;

import com.springtechkb.HashUtils;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class GenerationTelemetry {
    private static final ContextKey<PromptCatalog.Prompt> PROMPT = ContextKey.named("springkb.prompt");
    private final io.opentelemetry.api.trace.Tracer tracer;

    public GenerationTelemetry(OpenTelemetry telemetry) { this.tracer = telemetry.getTracer("springkb.generations"); }

    public <T> T observe(String operation, String callerKey, PromptCatalog.Prompt prompt, Supplier<T> action) {
        Span root = tracer.spanBuilder(operation).startSpan();
        attributes(root, operation, prompt);
        try (Scope rootScope = Context.current().with(root).with(PROMPT, prompt).makeCurrent()) {
            Span selection = tracer.spanBuilder("prompt.select").startSpan();
            attributes(selection, operation, prompt);
            selection.end();
            Span model = tracer.spanBuilder("gen_ai.generate").startSpan();
            attributes(model, operation, prompt);
            model.setAttribute("langfuse.observation.type", "generation");
            try (Scope modelScope = model.makeCurrent()) {
                return action.get();
            } catch (RuntimeException error) {
                model.setStatus(StatusCode.ERROR, error.getClass().getSimpleName());
                root.setStatus(StatusCode.ERROR, error.getClass().getSimpleName());
                throw error;
            } finally { model.end(); }
        } finally { root.end(); }
    }

    public static PromptCatalog.Prompt currentPrompt() { return Context.current().get(PROMPT); }

    public <T> Flux<T> observeFlux(String operation, PromptCatalog.Prompt prompt, Supplier<Flux<T>> action) {
        return Flux.defer(() -> {
            Span root = tracer.spanBuilder(operation).startSpan();
            attributes(root, operation, prompt);
            Flux<T> source;
            try (Scope ignored = Context.current().with(root).with(PROMPT, prompt).makeCurrent()) {
                source = action.get();
            } catch (RuntimeException error) {
                root.setStatus(StatusCode.ERROR, error.getClass().getSimpleName());
                root.end();
                return Flux.error(error);
            }
            return source.doOnError(error -> root.setStatus(StatusCode.ERROR, error.getClass().getSimpleName()))
                    .doFinally(signal -> root.end());
        });
    }

    public Span startSpan(String name) {
        Span span = tracer.spanBuilder(name).startSpan();
        PromptCatalog.Prompt prompt = currentPrompt();
        if (prompt != null) attributes(span, name, prompt);
        return span;
    }

    public <T> T inSpan(String name, Supplier<T> action) {
        Span span = startSpan(name);
        try (Scope ignored = span.makeCurrent()) {
            return action.get();
        } catch (RuntimeException error) {
            span.setStatus(StatusCode.ERROR, error.getClass().getSimpleName());
            throw error;
        } finally { span.end(); }
    }

    private static void attributes(Span span, String operation, PromptCatalog.Prompt prompt) {
        span.setAttribute("gen_ai.operation.name", operation);
        span.setAttribute("langfuse.trace.name", operation);
        span.setAttribute("langfuse.trace.public", false);
        span.setAttribute("langfuse.version", prompt.namespace());
        span.setAttribute("springkb.prompt.name", prompt.name());
        span.setAttribute("springkb.prompt.version", prompt.version());
        span.setAttribute("springkb.prompt.checksum", checksum(prompt.content()));
        // Local Prompt Registry versions are not Langfuse-managed prompts; do not fabricate prompt links.
        span.setAttribute("langfuse.observation.metadata.prompt_namespace", prompt.namespace());
    }

    public static String checksum(String content) {
        return HashUtils.sha256Hex(content);
    }
}
