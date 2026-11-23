package com.sixbean.testpike.aiservice.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;


@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens,
        List<ChatToolDef> tools,
        @JsonProperty("tool_choice") String toolChoice,
        Boolean stream,
        @JsonProperty("stream_options") StreamOptions streamOptions,
        /**
 * wiring — OpenAI-compatible prompt_cache_key: a stable, PII-free
 * routing hint attached only for multi-turn calls on cache-hint-enabled
 * models. Null (the default everywhere else) is never serialized.
 */
        @JsonProperty("prompt_cache_key") String promptCacheKey,
        /**
 * wiring — OpenAI-compatible response_format: schema-constrained
 * decoding for structured generation outputs. Null on every legacy
 * request (NON_NULL keeps the serialized shape byte-identical); the
 * consumption point attaches it only when the gate is on and the
 * endpoint is marked capable, and strips it again on provider refusal.
 */
        @JsonProperty("response_format") ChatResponseFormat responseFormat) {

    /** Legacy four-arg form (non-FC, non-streaming callers serialize zero drift). */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
                                 Double temperature, Integer maxTokens) {
        this(model, messages, temperature, maxTokens, null, null, null, null, null, null);
    }

    /** Legacy six-arg FC form (stream fields stay null — non-streaming callers). */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
                                 Double temperature, Integer maxTokens,
                                 List<ChatToolDef> tools, String toolChoice) {
        this(model, messages, temperature, maxTokens, tools, toolChoice, null, null, null, null);
    }

    /** Same request pinned to streaming with usage accounting in the tail chunk. */
    public ChatCompletionRequest asStreaming() {
        return new ChatCompletionRequest(model, messages, temperature, maxTokens,
                tools, toolChoice, Boolean.TRUE, StreamOptions.withUsage(), promptCacheKey,
                responseFormat);
    }

    /** Copy of this request carrying the provider prompt-cache key (null = unchanged shape). */
    public ChatCompletionRequest withPromptCacheKey(String cacheKey) {
        return new ChatCompletionRequest(model, messages, temperature, maxTokens,
                tools, toolChoice, stream, streamOptions, cacheKey, responseFormat);
    }

    /** Copy of this request carrying a response format (null = legacy shape unchanged). */
    public ChatCompletionRequest withResponseFormat(ChatResponseFormat format) {
        return new ChatCompletionRequest(model, messages, temperature, maxTokens,
                tools, toolChoice, stream, streamOptions, promptCacheKey, format);
    }

    /** Copy of this request carrying an explicit output cap (: the
 * extraction plane publishes its run's output ceiling as max_tokens). */
    public ChatCompletionRequest withMaxTokens(Integer capTokens) {
        return new ChatCompletionRequest(model, messages, temperature, capTokens,
                tools, toolChoice, stream, streamOptions, promptCacheKey, responseFormat);
    }

    /**
     * Copy of this request retargeted to another model — every other field rides
     * over untouched. The single structural fix for the fallback-chain rebuild:
     * positional copies here cleared response_format silently once (live window
     * w14), so a field added later can never be dropped again on degradation.
     */
    public ChatCompletionRequest withModel(String newModel) {
        return new ChatCompletionRequest(newModel, messages, temperature, maxTokens,
                tools, toolChoice, stream, streamOptions, promptCacheKey, responseFormat);
    }

    /** Copy of this request with the response format stripped (provider-refusal degrade). */
    public ChatCompletionRequest withoutResponseFormat() {
        return withResponseFormat(null);
    }

    public static ChatCompletionRequest of(String model, List<ChatMessage> messages) {
        return new ChatCompletionRequest(model, messages, null, null);
    }
}
