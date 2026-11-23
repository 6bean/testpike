package com.sixbean.testpike.aiservice.llm.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.EmbeddingCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmEmbeddingResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmStreamEvent;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmProperties;
import com.sixbean.testpike.aiservice.llm.domain.component.MergingToolCallFragment;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <ul>
 * </ul>
 *
 */
public class OpenAiCompatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    /** Exchange budget normalized at construction (single source: boundedExchangeBudget).
     *  The streaming body is handed back as a raw InputStream and JDK-spec request
     *  timeouts do not cover the body segment, so consumeStream re-arms this same
     *  budget as an absolute deadline over the read loop (slow-drip guard 2026-11-05). */
    private final Duration exchangeBudget;

    /** Inter-line idle bound for the streaming read loop (K8 ai.llm.stream-idle-timeout-seconds
     *  resolved per call by LlmTimeoutResolver; null = install default). */
    private final Duration streamIdleBudget;

    
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    public OpenAiCompatClient(String baseUrl, String apiKey, Duration timeout, ObjectMapper objectMapper) {
        this(baseUrl, apiKey, timeout, null, objectMapper);
    }

    public OpenAiCompatClient(String baseUrl, String apiKey, Duration timeout,
                              Duration streamIdleTimeout, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.exchangeBudget = boundedExchangeBudget(timeout, System.getenv("LLM_TIMEOUT_SECONDS"));
        this.streamIdleBudget = streamIdleTimeout != null
                ? streamIdleTimeout
                : Duration.ofSeconds(LlmProperties.DEFAULT_STREAM_IDLE_SECONDS);
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(CONNECT_TIMEOUT)
                                .build());
        factory.setReadTimeout(exchangeBudget);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * Exchange budget normalization (root cause 2026-11-05, rerun window 762): a
     * null budget handed to JdkClientHttpRequestFactory.setReadTimeout leaves the
     * pre-header exchange park unbounded — a stalling provider then hangs the
     * calling thread forever. Every client must therefore carry a real budget:
     * the caller-provided one wins; a null falls back to the installed single
     * source (env LLM_TIMEOUT_SECONDS, the same key the Spring binding reads),
     * then the LlmProperties factory default. The guard is never silently off
     * (same conservative-fallback discipline as ExtractDeadline). Per-call hot
     * updates keep flowing through LlmTimeoutResolver — this seam only closes
     * the construction-time hole where a caller forgot to pass a budget.
     */
    static Duration boundedExchangeBudget(Duration passed, String envRaw) {
        if (passed != null) {
            return passed;
        }
        if (envRaw != null && !envRaw.isBlank()) {
            try {
                int seconds = Integer.parseInt(envRaw.trim());
                if (seconds > 0) {
                    return Duration.ofSeconds(seconds);
                }
            } catch (NumberFormatException ignore) {
                // garbage install value falls back to the factory default, never off
            }
        }
        return Duration.ofSeconds(LlmProperties.DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * R56 model-request floor: the exchange budget of a production provider
     * call never goes below this bound. A hop minted smaller (a low K8 hot
     * value inside the old 10~900 save window, a zero meaning "never", or a
     * stale install fallback) silently caps the whole chain — the parked
     * read only wakes when the armed cancel fires, so long model outputs
     * turn into stall-and-retry loops (field shape: a generation task pinned
     * at CompletableFuture.get with the provider half gone). Values already
     * at or above the floor pass through untouched; over-800 manual budgets
     * stay put (R56).
     */
    public static final Duration MODEL_REQUEST_TIMEOUT_FLOOR =
            Duration.ofSeconds(800);

    /** Floor an explicitly minted budget (R56 assembly seam). */
    public static Duration floorModelRequestBudget(Duration budget) {
        if (budget == null || budget.isZero() || budget.isNegative()) {
            return MODEL_REQUEST_TIMEOUT_FLOOR;
        }
        return budget.compareTo(MODEL_REQUEST_TIMEOUT_FLOOR) < 0
                ? MODEL_REQUEST_TIMEOUT_FLOOR : budget;
    }

    /**
     * Normalize a caller/env budget through {@link #boundedExchangeBudget}
     * then apply the R56 floor — the single shape every production assembly
     * point must mint its budget with (LlmProviderFactory is the only
     * production constructor of this client).
     */
    public static Duration flooredExchangeBudget(Duration passed, String envRaw) {
        return floorModelRequestBudget(boundedExchangeBudget(passed, envRaw));
    }

    
    public LlmChatResult chat(ChatCompletionRequest request, String provider) {
        long start = System.currentTimeMillis();
        try {
            String body = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            long latencyMs = System.currentTimeMillis() - start;
            return parse(body, provider, request.model(), latencyMs);
        } catch (ServiceException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw translateAccessError(e);
        } catch (Exception e) {
            throw new ServiceException("AI 供应商调用失败: " + e.getMessage(), BizErrorCode.AI_PROVIDER_ERROR);
        }
    }

    
    public LlmEmbeddingResult embed(EmbeddingCompletionRequest request, String provider) {
        long start = System.currentTimeMillis();
        try {
            String body = restClient.post()
                    .uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            long latencyMs = System.currentTimeMillis() - start;
            return parseEmbeddings(body, provider, request.model(), latencyMs);
        } catch (ServiceException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw translateAccessError(e);
        } catch (RestClientResponseException e) {
            throw new ServiceException("AI 供应商调用失败: " + extractProviderError(e.getResponseBodyAsString())
                    + "（HTTP " + e.getStatusCode().value() + "）", BizErrorCode.AI_PROVIDER_ERROR);
        } catch (Exception e) {
            throw new ServiceException("AI 供应商调用失败: " + e.getMessage(), BizErrorCode.AI_PROVIDER_ERROR);
        }
    }

    /**
     * Streaming chat completion (AO batch dialog kernel): sends {@code stream:true}
     * and forwards every provider chunk to {@code onEvent} as it arrives, so the
     * caller can emit SSE frames without waiting for the whole answer.
     * <p>Return value keeps the same shape as {@link #chat}: the deltas already
     * streamed are aggregated into {@code content}, usage comes from the tail
     * chunk, and split tool-call fragments are merged by index. An upstream that
     * dies mid-stream is surfaced as a failure after delivering what it produced
     * — the caller keeps the partial text honestly instead of pretending completion.
     */
    public LlmChatResult chatStream(ChatCompletionRequest request, String provider,
                                    Consumer<LlmStreamEvent> onEvent) {
        long start = System.currentTimeMillis();
        ChatCompletionRequest streamRequest = request.asStreaming();
        try {
            return restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(streamRequest)
                    .exchange((clientRequest, clientResponse) -> {
                        int status = clientResponse.getStatusCode().value();
                        if (status >= 400) {
                            String errorBody = new String(clientResponse.getBody()
                                    .readAllBytes(), StandardCharsets.UTF_8);
                            throw new ServiceException("AI 供应商调用失败: "
                                    + extractProviderError(errorBody) + "（HTTP " + status + "）",
                                    BizErrorCode.AI_PROVIDER_ERROR);
                        }
                        return consumeStream(clientResponse.getBody(), onEvent, provider,
                                streamRequest.model(), System.currentTimeMillis() - start);
                    });
        } catch (ServiceException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw translateAccessError(e);
        } catch (Exception e) {
            throw new ServiceException("AI 供应商调用失败: " + e.getMessage(),
                    BizErrorCode.AI_PROVIDER_ERROR);
        }
    }

    /**
     * Reads the SSE byte stream line by line: ignores comment/blank lines, decodes
     * {@code data:} payloads, stops at {@code [DONE]}. EOF before {@code [DONE]}
     * means the upstream was cut — the error is raised after the handler already
     * saw every chunk that made it here.
     */
    private LlmChatResult consumeStream(java.io.InputStream body, Consumer<LlmStreamEvent> onEvent,
                                        String provider, String model, long latencyMs) {
        StringBuilder content = new StringBuilder();
        Map<Integer, MergingToolCallFragment> toolCallsByIndex = new LinkedHashMap<>();
        int[] usage = new int[3]; // [0]=prompt [1]=completion [2]=cached prefix tokens
        String actualModel = model == null ? "" : model;
        boolean doneSeen = false;
        // Slow-drip guard (root cause 2026-11-05, field evidence 763: a heartbeat-fed
        // drip ran 1801s). JDK request timeouts stop at the response headers, so the
        // body loop carries its own absolute deadline (single source: the exchange
        // budget normalized at construction) plus an inter-line idle bound (K8-hot
        // via LlmTimeoutResolver). The deadline anchors at REQUEST start (latencyMs
        // already elapsed when this loop begins) — the same instant the JDK counts
        // its own cancellation from — so a mid-body JDK cancel can never land inside
        // an unflagged window of our stricter clock. A watchdog closes the stream when
        // either bound is breached, so even a fully silent upstream cannot park the
        // read forever, and breaches map through the shared failure taxonomy — no
        // second classifier.
        final long deadlineNanos = System.nanoTime()
                - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(latencyMs)
                + exchangeBudget.toNanos();
        final long idleBoundNanos = streamIdleBudget.toNanos();
        java.util.concurrent.atomic.AtomicLong lastLineNanos =
                new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        java.util.concurrent.atomic.AtomicReference<String> breach =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean exchangeOver =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread guard = new Thread(() -> {
            while (!exchangeOver.get() && breach.get() == null) {
                long now = System.nanoTime();
                if (now >= deadlineNanos) {
                    breach.compareAndSet(null, "交换预算(绝对 deadline)");
                } else if (now - lastLineNanos.get() >= idleBoundNanos) {
                    breach.compareAndSet(null, "线间空闲上限");
                }
                if (breach.get() != null) {
                    try {
                        body.close();
                    } catch (Exception ignored) {
                        // unblocking the parked read is all the guard needs
                    }
                    return;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "llm-stream-guard");
        guard.setDaemon(true);
        guard.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastLineNanos.set(System.nanoTime());
                if (!line.startsWith("data:")) {
                    continue; // SSE comment (":hb") and blank separator lines
                }
                String payload = line.substring(5).strip();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    doneSeen = true;
                    break;
                }
                JsonNode chunk = objectMapper.readTree(payload);
                if (!chunk.path("model").asText("").isEmpty()) {
                    actualModel = chunk.path("model").asText();
                }
                JsonNode usageNode = chunk.path("usage");
                if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                    usage[0] = usageNode.path("prompt_tokens").asInt(usage[0]);
                    usage[1] = usageNode.path("completion_tokens").asInt(usage[1]);
                    // Same details block as the non-streaming parse path: reused-prefix
                    // counts ride prompt_tokens_details.cached_tokens, absent = honest 0.
                    usage[2] = usageNode.path("prompt_tokens_details")
                            .path("cached_tokens").asInt(usage[2]);
                }
                for (JsonNode choice : chunk.path("choices")) {
                    JsonNode delta = choice.path("delta");
                    String reason = delta.path("reasoning_content").asText("");
                    if (!reason.isEmpty()) {
                        onEvent.accept(new LlmStreamEvent(LlmStreamEvent.KIND_REASON, reason));
                    }
                    String piece = delta.path("content").asText("");
                    if (!piece.isEmpty()) {
                        content.append(piece);
                        onEvent.accept(new LlmStreamEvent(LlmStreamEvent.KIND_CONTENT, piece));
                    }
                    for (JsonNode call : delta.path("tool_calls")) {
                        int index = call.path("index").asInt(0);
                        toolCallsByIndex.computeIfAbsent(index, k -> new MergingToolCallFragment())
                                .absorb(call);
                    }
                }
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            // A guard breach may surface here before the polling guard had time to
            // record it (the JDK cancels the exchange right at the request timeout,
            // and that cancellation lands microseconds ahead of our 50ms poll).
            // Re-check both bounds on the spot so the race still maps to the honest
            // model-timeout instead of a parse failure.
            String reason = breach.get();
            if (reason == null) {
                long now = System.nanoTime();
                if (now >= deadlineNanos) {
                    reason = "交换预算(绝对 deadline)";
                } else if (now - lastLineNanos.get() >= idleBoundNanos) {
                    reason = "线间空闲上限";
                }
            }
            if (reason != null) {
                clearGuardInterruptResidue();
                throw streamGuardTimeout(reason);
            }
            throw new ServiceException("AI 流式响应解析失败: " + e.getMessage(),
                    BizErrorCode.AI_FORMAT_ERROR);
        } finally {
            exchangeOver.set(true);
        }
        String reason = breach.get();
        if (reason != null) {
            clearGuardInterruptResidue();
            throw streamGuardTimeout(reason);
        }
        if (!doneSeen) {
            throw new ServiceException("AI 流式响应在未收尾前中断", BizErrorCode.AI_PROVIDER_ERROR);
        }
        List<LlmToolCall> merged = toolCallsByIndex.values().stream()
                .filter(MergingToolCallFragment::isPresentable)
                .map(MergingToolCallFragment::toCall)
                .toList();
        return new LlmChatResult(content.toString(), provider, actualModel, usage[0], usage[1],
                latencyMs, merged.isEmpty() ? null : merged, usage[2]);
    }

    /**
 * Guard self-close interrupt hygiene (field attribution 2026-11-17,
 * extraction docs 9077-9079). The watchdog closes a stalled body from
 * another thread; the JDK wakes the parked reader here with the thread
 * interrupt flag left set. That residue is produced by our own close, so
 * it is ours to clear: left in place it instantly fails every later
 * interrupt-aware step on the same worker (JDBC connection fetch, retry
 * sleep, downstream call), collapsing all retries into one second and
 * burying the real timeout under "Failed to obtain JDBC Connection".
 * A cooperative cancellation never records a breach and is
 * deliberately not cleared on that path — abort semantics stay intact.
 */
    private static void clearGuardInterruptResidue() {
        if (Thread.interrupted()) {
            log.debug("stream guard cleared its own interrupt residue from the cancelled body read");
        }
    }

    /**
     * A stream-guard breach surfaces as the standard model timeout through the
     * shared taxonomy (translateAccessError) — the timeout classification stays
     * one home, no second site for 50401 semantics (red line 6 single source).
     */
    private ServiceException streamGuardTimeout(String reason) {
        // carry the breach as HttpTimeoutException (IOException family, already a
        // timeout signal in the taxonomy) so 50401 semantics keep one home
        java.net.http.HttpTimeoutException tex =
                new java.net.http.HttpTimeoutException("llm stream guard: " + reason);
        return translateAccessError(new ResourceAccessException("llm stream guard: " + reason, tex));
    }

    /** Shared failure taxonomy for unreachable/timeout upstreams (chat and embed alike). */
    private ServiceException translateAccessError(ResourceAccessException e) {
        Throwable cause = e.getCause();
        if (cause instanceof SocketTimeoutException
                || cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException) {
            return new ServiceException("模型调用超时", BizErrorCode.AI_TIMEOUT);
        }
        return new ServiceException("AI 供应商不可达: " + e.getMessage(), BizErrorCode.AI_PROVIDER_ERROR);
    }

    
    private String extractProviderError(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String message = root.path("error").path("message").asText("");
            if (!message.isBlank()) {
                return message;
            }
        } catch (Exception ignored) {
        }
        String raw = responseBody == null ? "" : responseBody.strip();
        return raw.isEmpty() ? "空响应" : raw.substring(0, Math.min(raw.length(), 200));
    }

    
    LlmEmbeddingResult parseEmbeddings(String responseBody, String provider, String model, long latencyMs) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            List<JsonNode> items = new ArrayList<>();
            root.path("data").forEach(items::add);
            items.sort(Comparator.comparingInt(node -> node.path("index").asInt(0)));
            List<float[]> embeddings = new ArrayList<>(items.size());
            for (JsonNode item : items) {
                JsonNode vector = item.path("embedding");
                float[] vec = new float[vector.size()];
                for (int i = 0; i < vec.length; i++) {
                    vec[i] = (float) vector.get(i).asDouble();
                }
                embeddings.add(vec);
            }
            int totalTokens = root.path("usage").path("total_tokens").asInt(0);
            String actualModel = root.path("model").asText(model == null ? "" : model);
            return new LlmEmbeddingResult(embeddings, provider, actualModel, totalTokens, latencyMs);
        } catch (Exception e) {
            throw new ServiceException("AI 响应格式解析失败: " + e.getMessage(), BizErrorCode.AI_FORMAT_ERROR);
        }
    }

    
    LlmChatResult parse(String responseBody, String provider, String model, long latencyMs) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            String content = "";
            List<LlmToolCall> toolCalls = null;
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                content = message.path("content").asText("");
                JsonNode calls = message.path("tool_calls");
                if (calls.isArray()) {
                    toolCalls = new ArrayList<>(calls.size());
                    for (JsonNode call : calls) {
                        JsonNode fn = call.path("function");
                        toolCalls.add(new LlmToolCall(
                                call.path("id").asText(""),
                                fn.path("name").asText(""),
                                fn.path("arguments").asText("")));
                    }
                }
            }
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            // reused-prefix tokens ride usage.prompt_tokens_details
            // .cached_tokens (OpenAI-compatible); absence honestly reads 0 — the
            // calibration page shows that zero rather than inventing a number.
            int cachedTokens = usage.path("prompt_tokens_details")
                    .path("cached_tokens").asInt(0);
            String actualModel = root.path("model").asText(model == null ? "" : model);
            return new LlmChatResult(content, provider, actualModel, promptTokens,
                    completionTokens, latencyMs, toolCalls, cachedTokens);
        } catch (Exception e) {
            throw new ServiceException("AI 响应格式解析失败: " + e.getMessage(), BizErrorCode.AI_FORMAT_ERROR);
        }
    }
}
