package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.aiservice.aichat.domain.component.ChatUserImageEvidence;
import com.sixbean.testpike.aiservice.aichat.domain.component.ChatUserImagePromptTemplate;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureScreenshotBytesFetcher;
import com.sixbean.testpike.aiservice.devtoolspanel.api.dto.PanelStageOutcome;
import com.sixbean.testpike.aiservice.devtoolspanel.domain.service.ScreenshotLoginStateStager;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrFallbackOutcome;
import com.sixbean.testpike.aiservice.ocr.domain.service.OcrTextFallbackReader;
import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;

/**
 * Dialog user-image vision round-trip : when a
 * question carries user-image attachments and this application point is
 * switched on, every image is fetched through core's chat-image read door,
 * described by the configured vision model, and the observations ride the
 * system prompt next to the question — the mobile-photo bug-report flow, so
 * the model sees what the user saw instead of guessing from prose.
 * <p>Gate posture (7a one-application-point-one-switch, R32 shipped-on) mirrors
 * the failure-screenshot point exactly:
 * <ul>
 * <li>no attachments / sub-switch {@code ai.vision.chat-user-image} off =
 * silent pass-through: the deterministic text-only chain, zero door reads,
 * zero model calls;</li>
 * <li>sub-switch on but master gate {@code ai.vision.enabled} off = ONE
 * honest state frame naming both literal keys and where to open them;</li>
 * <li>model key {@code ai.vision.model} blank = refused with the key named —
 * the project text router is NEVER borrowed to pretend vision;</li>
 * <li>every image leg fails (door down / bytes unreadable / provider
 * error) = degraded with the reasons named; a mixed outcome stays attached
 * and names the failed images inside the block (never a silently dropped
 * photo — the user must be able to tell which picture the answer saw).</li>
 * </ul>
 * <p>Bytes posture (stage-2 fix inherited from , live-proven ):
 * the presigned GET url points at the deployment's own storage host, which
 * cloud vision providers cannot reach server-side, so the bytes are fetched
 * once through {@link FailureScreenshotBytesFetcher} and inlined as a base64
 * data url. The ownership gate and storage routing stay single-sourced in
 * core's door — only the bytes transit, and only after that door approved.
 * <p>Cost posture: one call_log row per model round-trip, size cap single-
 * sourced with the failure-screenshot point ({@code
 * ai.vision.failure-screenshot-max-bytes}, hot-tunable).
 */
@Service
public class ChatUserImageResolver {

    /** Vision master gate (shared upstream switch, V155 seed). */
    static final String KEY_VISION_ENABLED = "ai.vision.enabled";
    /** This application point's own switch (V334 seed, shipped on per R32). */
    static final String KEY_POINT_ENABLED = "ai.vision.chat-user-image";
    /** Vision model key (single-sourced with visual locate, V156 seed). */
    static final String KEY_MODEL = "ai.vision.model";
    /** Hard cap of the inlined image bytes (V309 seed, shared with ). */
    static final String KEY_MAX_BYTES = "ai.vision.failure-screenshot-max-bytes";
    /** Vision-less OCR fallback gate . */
    static final String KEY_OCR_FALLBACK_ENABLED = "ai.ocr.fallback.enabled";
    /** Density floor for the OCR dense verdict (V520 seed, default 3, hot-tunable). */
    static final String KEY_OCR_LINE_MIN = "ai.ocr.fallback-line-min";
    /** Grid-assist character-post key ( supplement item 2, V523 seed,
 * factory on): with a vision model configured the deterministic OCR
 * baseline still rides the read round's observation block. Orthogonal
 * to the ai.ocr.fallback.* family — each key family gates its own
 * branch, mixing is forbidden (adjudication sheet §6). */
    static final String KEY_OCR_GRID_ASSIST_ENABLED = "ai.ocr.grid-assist.enabled";
    /** Floor fallback when the key is absent/illegal. */
    static final int DEFAULT_MIN_LINES = 3;
    /** Fallback cap when the key is absent/illegal = 8 MB. */
    static final int DEFAULT_MAX_BYTES = 8 * 1024 * 1024;
    /** tp_ai_call_log function label for this application point. */
    private static final String FUNCTION_NAME = "对话用户图读图";
    /** Prompt-weight bound of one image's observation. */
    private static final int OBSERVATION_MAX_CHARS = 600;

    private static final Logger log = LoggerFactory.getLogger(ChatUserImageResolver.class);

    /** Behaviour-directive gate key for user-image reading (factory default on). */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.aichat.user-image-directives.enabled";

    /** Template family (tp_prompt_template name/domain pair). */
    public static final String TEMPLATE_NAME = "aichat-user-image-directives";
    public static final String TEMPLATE_DOMAIN = "aichat-dialog-image";

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final AiCallLogWriter aiCallLogWriter;
    private final ChatUserImagePromptTemplate promptTemplate;
    private final FailureScreenshotBytesFetcher bytesFetcher;
    private final ScreenshotLoginStateStager panelStager;
    private final OcrTextFallbackReader fallbackReader;

    @Autowired
    public ChatUserImageResolver(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                 AiCallLogWriter aiCallLogWriter,
                                 ChatUserImagePromptTemplate promptTemplate,
                                 FailureScreenshotBytesFetcher bytesFetcher,
                                 ScreenshotLoginStateStager panelStager,
                                 OcrTextFallbackReader fallbackReader) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.aiCallLogWriter = aiCallLogWriter;
        this.promptTemplate = promptTemplate;
        this.bytesFetcher = bytesFetcher;
        this.panelStager = panelStager;
        this.fallbackReader = fallbackReader;
    }

    /** Seam without the panel stager / fallback reader (head-directive tests). */
    public ChatUserImageResolver(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                 AiCallLogWriter aiCallLogWriter,
                                 ChatUserImagePromptTemplate promptTemplate,
                                 FailureScreenshotBytesFetcher bytesFetcher,
                                 ScreenshotLoginStateStager panelStager) {
        this(coreGateway, llmFallbackChain, aiCallLogWriter, promptTemplate, bytesFetcher,
                panelStager, null);
    }

    /** Seam without the panel stager (head-directive tests): pure vision leg. */
    public ChatUserImageResolver(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                 AiCallLogWriter aiCallLogWriter,
                                 ChatUserImagePromptTemplate promptTemplate,
                                 FailureScreenshotBytesFetcher bytesFetcher) {
        this(coreGateway, llmFallbackChain, aiCallLogWriter, promptTemplate, bytesFetcher,
                null, null);
    }

    /**
     * Behaviour-kernel head for one image read (R40 single source: gate,
     * read and degrade sequence live in BehaviorKernelAssembly). A closed
     * gate performs zero template reads; a failing read degrades to the
     * built-in observation discipline and never takes the read down.
     */
    String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                KEY_DIRECTIVES_ENABLED, TEMPLATE_NAME, TEMPLATE_DOMAIN,
                ChatUserImagePromptTemplate.SYSTEM,
                "行为指令模板读路径不可用，本轮用户图片读图回退内置契约");
    }

    /**
     * Describe every image attached to one question. A photoless turn
     * short-circuits to {@link ChatUserImageEvidence#NONE} before the gates
     * are even read — zero config reads, zero door calls, the exact old shape.
     */
    public ChatUserImageEvidence resolve(long projectId, long chatSessionId,
                                         List<ChatImageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return ChatUserImageEvidence.NONE;
        }
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_POINT_ENABLED))) {
            // Sub-switch off: application point dormant, deterministic chain untouched.
            return ChatUserImageEvidence.NONE;
        }
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_VISION_ENABLED))) {
            return ChatUserImageEvidence.absent(DenialGuidanceFrame.switchClosed(
                    "用户图片读图应用点", KEY_VISION_ENABLED, KEY_POINT_ENABLED)
                    + "；本次回答仅基于提问文字。");
        }
        String model = coreGateway.getSysConfig(KEY_MODEL);
        if (model == null || model.isBlank()) {
            // B1b: vision-less OCR fallback — text-dense screenshots
            // get a provenance-marked transcription instead of a flat refusal;
            // the legacy honest guidance stays for fallback-off or a seam
            // without the reader (the text router still NEVER pretends vision).
            if (fallbackReader == null || !SysConfigBooleans.on(
                    coreGateway.getSysConfig(KEY_OCR_FALLBACK_ENABLED))) {
                return ChatUserImageEvidence.absent("用户图片读图应用点未执行：视觉模型未配置（系统设置-"
                        + KEY_MODEL + "），不会改用文本模型假装视觉；本次回答仅基于提问文字。");
            }
            return resolveByVisionLessOcr(projectId, attachments);
        }
        int maxBytes = SysConfigInts.positiveInt(
                coreGateway.getSysConfig(KEY_MAX_BYTES), DEFAULT_MAX_BYTES);
        StringBuilder block = new StringBuilder();
        StringBuilder panelBlock = new StringBuilder();
        List<String> failureLines = new ArrayList<>();
        List<String> roundStagingTokens = new ArrayList<>();
        int read = 0;
        for (ChatImageAttachment attachment : attachments) {
            String label = attachment.fileName() == null || attachment.fileName().isBlank()
                    ? attachment.objectKey() : attachment.fileName();
            try {
                String url = coreGateway.chatImageUrl(projectId, attachment.objectKey());
                byte[] image = bytesFetcher.fetch(url, maxBytes);
                // piece D segment b: every image whose bytes came through
                // also gets the DevTools-panel leg (OCR + grid aligner + stage
                // door). Switch-closed or failed = silent NONE inside the
                // stager — this leg can never take the vision round down.
                PanelStageOutcome stage = panelStager == null
                        ? PanelStageOutcome.NONE
                        : panelStager.stageFromScreenshot(chatSessionId, image);
                if (stage != null && stage.staged()) {
                    renderPanelSummary(panelBlock, stage);
                    // B1 reconcile input: the token this round parked is a
                    // slot pointer (the observation block already shows it),
                    // kept alongside so the draft emitter can re-hang a
                    // transcription that routes to a dead slot.
                    roundStagingTokens.add(stage.token());
                }
                String dataUrl = "data:" + mimeOf(label) + ";base64,"
                        + Base64.getEncoder().encodeToString(image);
                LlmChatHandle handle = llmFallbackChain.chatHandle(
                        ChatCompletionRequest.of(model, promptTemplate.messages(behaviorDirectivesHead(), label, dataUrl)),
                        projectId, null);
                aiCallLogWriter.insert(projectId, handle.result().provider(),
                        handle.result().model(), FUNCTION_NAME,
                        handle.result().promptTokens(), handle.result().completionTokens(),
                        handle.result().latencyMs());
                String observation = handle.result().content() == null
                        ? "" : handle.result().content().strip();
                if (observation.isEmpty()) {
                    throw new IllegalStateException("Vision model returned no readable description for this image");
                }
                read++;
                block.append("【图片《").append(label).append("》观察】")
                        .append(bound(observation)).append('\n');
                appendGridAssistNote(block, image, observation);
                appendHandleNote(block, attachment.objectKey());
            } catch (RuntimeException legFailure) {
                // Enhancement leg: name the failed image, keep the good legs.
                log.warn("Chat user image reading degraded projectId={} key={} err={}",
                        projectId, attachment.objectKey(), legFailure.getMessage());
                failureLines.add(label + "：" + legFailure.getMessage());
                block.append("【图片《").append(label).append("》未读取成功】")
                        .append(bound(legFailure.getMessage())).append('\n');
                appendHandleNote(block, attachment.objectKey());
            }
        }
        if (read == 0) {
            return ChatUserImageEvidence.absent("用户图片读取未完成（"
                    + ChatUserImageEvidence.joinFailures(failureLines)
                    + "），本次回答仅基于提问文字。");
        }
        if (panelBlock.length() > 0) {
            block.append(panelBlock);
        }
        log.info("Chat user image reading done projectId={} success={} failed={}",
                projectId, read, failureLines.size());
        return ChatUserImageEvidence.attached(block.toString().strip(), read, roundStagingTokens);
    }

    /**
 * Vision-less read leg (BF batch half-slice B1b, adjudication sheet
 * door, transcribed by the shared {@link OcrTextFallbackReader} against
 * the hot-tunable density floor, and only text-dense results ride the
 * outbound prompt — inside the provenance marker pair, with the honest
 * "transcription, not image understanding" notice (R32 dual-factor
 * guidance). Sparse images refuse with their line count named and engine
 * failures keep their reason verbatim; nothing is ever force-interpreted
 * and zero model rounds are burned (local engine, no call_log rows).
 */
    private ChatUserImageEvidence resolveByVisionLessOcr(long projectId,
                                                          List<ChatImageAttachment> attachments) {
        int maxBytes = SysConfigInts.positiveInt(
                coreGateway.getSysConfig(KEY_MAX_BYTES), DEFAULT_MAX_BYTES);
        int minLines = SysConfigInts.positiveInt(
                coreGateway.getSysConfig(KEY_OCR_LINE_MIN), DEFAULT_MIN_LINES);
        StringBuilder block = new StringBuilder();
        List<String> failureLines = new ArrayList<>();
        int read = 0;
        for (ChatImageAttachment attachment : attachments) {
            String label = attachment.fileName() == null || attachment.fileName().isBlank()
                    ? attachment.objectKey() : attachment.fileName();
            try {
                String url = coreGateway.chatImageUrl(projectId, attachment.objectKey());
                byte[] image = bytesFetcher.fetch(url, maxBytes);
                OcrFallbackOutcome outcome = fallbackReader.read(image, minLines);
                if (outcome == null) {
                    throw new IllegalStateException("OCR fallback reading returned nothing");
                }
                if (outcome.textDense()) {
                    read++;
                    block.append("【图片《").append(label).append("》文字提取（OCR）】")
                            .append(OcrTextFallbackReader.boundTranscription(
                                    outcome.derivedText())).append('\n');
                    appendHandleNote(block, attachment.objectKey());
                } else {
                    String why = outcome.failureReason() != null
                            ? outcome.failureReason()
                            : OcrTextFallbackReader.sparseReason(outcome.lineCount());
                    failureLines.add(label + "：" + why);
                    block.append("【图片《").append(label).append("》未如实提取】").append(why).append('\n');
                    appendHandleNote(block, attachment.objectKey());
                }
            } catch (RuntimeException legFailure) {
                log.warn("Chat user image OCR fallback reading failed projectId={} key={} err={}",
                        projectId, attachment.objectKey(), legFailure.getMessage());
                failureLines.add(label + "：" + legFailure.getMessage());
                block.append("【图片《").append(label).append("》未如实提取】")
                        .append(bound(legFailure.getMessage())).append('\n');
                appendHandleNote(block, attachment.objectKey());
            }
        }
        if (read == 0) {
            return ChatUserImageEvidence.absent("用户图片 OCR 降级读取未完成（"
                    + ChatUserImageEvidence.joinFailures(failureLines)
                    + "）；视觉模型未配置（系统设置-" + KEY_MODEL
                    + "），配置后自动改走图像理解；本次回答仅基于提问文字。");
        }
        log.info("Chat user image OCR fallback reading done projectId={} success={} unextracted={}",
                projectId, read, failureLines.size());
        return ChatUserImageEvidence.attached(OcrTextFallbackReader.degradedNotice(KEY_MODEL)
                + "\n" + block.toString().strip(), read, List.of());
    }

    /**
 * Value-free rendering of one staged panel : the
 * staging token plus kind/name/origin rows ride the observation block so
 * the answer can offer the draft flow; the credential values stayed in
 * the transient slot and appear nowhere in this text (piece E red line).
 */
    private static void renderPanelSummary(StringBuilder block, PanelStageOutcome stage) {
        // Declarative form, no assignment operator: the pre-send redaction
        // gate masks "token=<value>" shapes, and this slot pointer is not a
        // credential - masking it would blind the register round (piece F
        // defect).
        block.append("【DevTools 面板解析摘要】暂存凭证 ").append(stage.token())
                .append("（stagingToken 填此值）\n");
        for (String line : stage.metadataLines()) {
            block.append("- ").append(line).append('\n');
        }
        block.append("共 ").append(stage.candidateCount())
                .append(" 条登录态候选已暂存（勾选确认后会走登录态入库草案流程）");
        if (stage.truncatedSuspectCount() > 0) {
            block.append("；另有 ").append(stage.truncatedSuspectCount())
                    .append(" 行取值疑似被列宽截断，未纳入（放大对应分区重新截图可补全）");
        }
        block.append("。\n");
    }

    /**
 * handle disclosure: every image block line is followed by the
 * booked session handle, so a wording-selected read-only image tool can
 * be pointed at the exact stored picture instead of only its display
 * name (the ownership door refuses anything it cannot match).
 */
    private static void appendHandleNote(StringBuilder block, String objectKey) {
        block.append("〔会话句柄 ").append(objectKey).append("〕\n");
    }

    /**
 * Character post beside the vision leg (BF batch supplement item 2,
 * adjudication sheet §6): while {@code ai.ocr.grid-assist.enabled} is on,
 * the same image bytes also go through the shared {@link
 * OcrTextFallbackReader} (local engine, zero model rounds, floor 1 — the
 * baseline is deliberately NOT gated by the fallback family's density
 * key, the two families each gate their own branch) and the deterministic
 * transcription rides the observation block inside the {@code
 * <ocr_derived>} provenance markers. Value-shaped tokens that the baseline
 * carries but the vision description misses verbatim are disagreements:
 * named inside the block and WARNed in the log (never silent, R43), the
 * OCR baseline wins for value fields while the vision text acts as
 * semantic correction. A failed or sparse assist leg adds no section and
 * can never take the vision round down.
 */
    private void appendGridAssistNote(StringBuilder block, byte[] image, String observation) {
        if (fallbackReader == null || !SysConfigBooleans.on(
                coreGateway.getSysConfig(KEY_OCR_GRID_ASSIST_ENABLED))) {
            return;
        }
        try {
            OcrFallbackOutcome assist = fallbackReader.read(image, 1);
            if (assist == null || !assist.textDense()) {
                return;
            }
            block.append("【字符岗 OCR 初值】\n")
                    .append(OcrTextFallbackReader.boundTranscription(assist.derivedText()))
                    .append('\n');
            List<String> missed = assistDisagreements(assist.derivedText(), observation);
            if (missed.isEmpty()) {
                block.append("〔双岗对账〕视觉转录与 OCR 初值一致（0 处分歧）。\n");
                return;
            }
            block.append("〔双岗对账〕").append(missed.size())
                    .append(" 处值型 token 在 OCR 初值而视觉转录未见原形（")
                    .append(String.join("、", missed.subList(0, Math.min(5, missed.size()))))
                    .append("）：值型字段以 OCR 初值为基准，视觉描述作语义校正。\n");
            log.warn("Chat image vision/OCR divergence count={} keys={}", missed.size(), String.join(",", missed));
        } catch (RuntimeException assistFailure) {
            log.warn("Chat image OCR initial-value leg degraded (vision turn unaffected): {}", assistFailure.getMessage());
        }
    }

    /** Value-shaped token shapes of the baseline: hyphen-joined ids and long digit runs. */
    private static final java.util.regex.Pattern ASSIST_VALUE_TOKEN =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9]{1,19}(?:-[A-Za-z0-9]+)+|\\d{8,}");

    /** Distinct baseline tokens the vision transcription does not carry verbatim. */
    private static List<String> assistDisagreements(String baseline, String observation) {
        java.util.LinkedHashSet<String> missed = new java.util.LinkedHashSet<>();
        String seen = observation == null ? "" : observation;
        java.util.regex.Matcher matcher = ASSIST_VALUE_TOKEN.matcher(baseline == null ? "" : baseline);
        while (matcher.find()) {
            String token = matcher.group();
            if (!seen.contains(token)) {
                missed.add(token);
            }
        }
        return new ArrayList<>(missed);
    }

    /** MIME from the file extension (upload door already pinned the magic). */
    private static String mimeOf(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/png";
    }

    private static String bound(String raw) {
        return raw.length() <= OBSERVATION_MAX_CHARS
                ? raw : raw.substring(0, OBSERVATION_MAX_CHARS) + "…";
    }
}
