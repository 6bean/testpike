package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureDiagnosis;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureScenePromptTemplate;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureScreenshotBytesFetcher;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureScreenshotEvidence;
import com.sixbean.testpike.aiservice.aichat.infrastructure.remote.TaskScreenshotView;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrFallbackOutcome;
import com.sixbean.testpike.aiservice.ocr.domain.service.OcrTextFallbackReader;

/**
 * Failure-scene screenshot round-trip (AO batch AO-8 screenshot
 * multimodal): when a badge-deep-link question carries a RESOLVED diagnosis
 * and the vision application point is switched on, the last screenshot
 * artifact of that execution is fetched through core's F-8 door, read by the
 * configured vision model, and the resulting observation rides the dialog
 * prompt next to the diagnosis.
 * <p>Gate posture (7a one-application-point-one-switch, R32 shipped-on):
 * <ul>
 * <li>sub-switch {@code ai.vision.failure-screenshot} off = silent pass-
 * through: the deterministic text-only chain, zero artifact reads, zero
 * model calls, zero extra frames (acceptance = "off state returns to the
 * deterministic chain with no half-baked side effects");</li>
 * <li>sub-switch on but the master gate {@code ai.vision.enabled} off =
 * ONE honest state frame naming both literal keys and where to open them
 * (system settings) — someone configured the sub-switch, so the refusal
 * must say which door blocked it, never silence;</li>
 * <li>model key {@code ai.vision.model} blank = refused with the key named
 * — the project text router is NEVER borrowed to pretend vision (a text
 * model receiving no image would confidently invent a scene);</li>
 * <li>missing artifact / door down / provider failure = degraded with the
 * reason named, the text-only turn still runs (the screenshot is an
 * enhancement, blocking the whole answer on it would be worse).</li>
 * </ul>
 * <p>Storage posture (stage-2 fix, live-proven ): the presigned GET
 * url points at the deployment's own storage host, which cloud vision
 * providers cannot reach server-side (Dashscope rejects it with 400), so this
 * service downloads the artifact bytes once through {@link
 * FailureScreenshotBytesFetcher} and inlines them as a base64 data url — the
 * same shape VisualLocateServiceImpl ships. The ownership gate and storage
 * routing stay single-sourced in core's F-8 door; only the bytes transit, and
 * only after that door approved the url.
 * <p>Cost posture: one call_log row per model round-trip through
 * {@link AiCallLogWriter} (same door as every other application point).
 */
@Service
public class FailureScreenshotResolver {

    /** Vision master gate (shared upstream switch, V155 seed). */
    static final String KEY_VISION_ENABLED = "ai.vision.enabled";
    /** This application point's own switch (V308 seed, shipped on per R32). */
    static final String KEY_POINT_ENABLED = "ai.vision.failure-screenshot";
    /** Vision model key (single-sourced with visual locate, V156 seed). */
    static final String KEY_MODEL = "ai.vision.model";
    /** Hard cap of the inlined screenshot bytes (V309 seed, hot-tunable). */
    static final String KEY_MAX_BYTES = "ai.vision.failure-screenshot-max-bytes";
    /** Vision-less OCR fallback gate . */
    static final String KEY_OCR_FALLBACK_ENABLED = "ai.ocr.fallback.enabled";
    /** Density floor for the OCR dense verdict (V520 seed, default 3, hot-tunable). */
    static final String KEY_OCR_LINE_MIN = "ai.ocr.fallback-line-min";
    /** Floor fallback when the key is absent/illegal. */
    static final int DEFAULT_MIN_LINES = 3;
    /** Fallback cap when the key is absent/illegal = 8 MB. */
    static final int DEFAULT_MAX_BYTES = 8 * 1024 * 1024;
    /** tp_ai_call_log function label for this application point. */
    private static final String FUNCTION_NAME = "对话失败现场读图";
    /** Prompt-weight bound of the vision observation block. */
    private static final int SCENE_MAX_CHARS = 600;

    private static final Logger log = LoggerFactory.getLogger(FailureScreenshotResolver.class);

    /** Behaviour-directive gate key for failure-screenshot reading (factory default on). */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.aichat.failure-scene-directives.enabled";

    /** Template family (tp_prompt_template name/domain pair). */
    public static final String TEMPLATE_NAME = "aichat-failure-scene-directives";
    public static final String TEMPLATE_DOMAIN = "aichat-failure-scene";

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final AiCallLogWriter aiCallLogWriter;
    private final FailureScenePromptTemplate promptTemplate;
    private final FailureScreenshotBytesFetcher bytesFetcher;
    private final OcrTextFallbackReader fallbackReader;

    @Autowired
    public FailureScreenshotResolver(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                     AiCallLogWriter aiCallLogWriter,
                                     FailureScenePromptTemplate promptTemplate,
                                     FailureScreenshotBytesFetcher bytesFetcher,
                                     OcrTextFallbackReader fallbackReader) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.aiCallLogWriter = aiCallLogWriter;
        this.promptTemplate = promptTemplate;
        this.bytesFetcher = bytesFetcher;
        this.fallbackReader = fallbackReader;
    }

    /** Seam without the fallback reader (head-directive tests): legacy refusal on blank model. */
    public FailureScreenshotResolver(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                     AiCallLogWriter aiCallLogWriter,
                                     FailureScenePromptTemplate promptTemplate,
                                     FailureScreenshotBytesFetcher bytesFetcher) {
        this(coreGateway, llmFallbackChain, aiCallLogWriter, promptTemplate, bytesFetcher, null);
    }

    /**
     * Behaviour-kernel head for one scene read (R40 single source): the
     * gate/read/degrade sequence lives in BehaviorKernelAssembly. The
     * failure detail anchor stays code-side in the user turn.
     */
    String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                KEY_DIRECTIVES_ENABLED, TEMPLATE_NAME, TEMPLATE_DOMAIN,
                FailureScenePromptTemplate.SYSTEM,
                "行为指令模板读路径不可用，本轮失败现场读图回退内置契约");
    }

    /**
     * Try the screenshot round-trip for one resolved diagnosis. Anything that
     * is not a resolved diagnosis short-circuits to {@link
     * FailureScreenshotEvidence#NONE} before the gates are even read — no
     * execution on the table means no scene to look at.
     */
    public FailureScreenshotEvidence resolve(long projectId, FailureDiagnosis diagnosis) {
        if (diagnosis == null || !diagnosis.resolved()) {
            return FailureScreenshotEvidence.NONE;
        }
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_POINT_ENABLED))) {
            // Sub-switch off: application point dormant, deterministic chain untouched.
            return FailureScreenshotEvidence.NONE;
        }
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_VISION_ENABLED))) {
            return FailureScreenshotEvidence.absent(DenialGuidanceFrame.switchClosed(
                    "失败现场截图读图应用点", KEY_VISION_ENABLED, KEY_POINT_ENABLED)
                    + "；本次回答仅基于失败详情文本。");
        }
        String model = coreGateway.getSysConfig(KEY_MODEL);
        if (model == null || model.isBlank()) {
            // B1b: vision-less OCR fallback for the failure scene
            // attachment point); fallback off keeps the legacy refusal.
            if (fallbackReader == null || !SysConfigBooleans.on(
                    coreGateway.getSysConfig(KEY_OCR_FALLBACK_ENABLED))) {
                return FailureScreenshotEvidence.absent("截图读图应用点未执行：视觉模型未配置（系统设置-"
                        + KEY_MODEL + "），不会改用文本模型假装视觉；本次回答仅基于失败详情文本。");
            }
            return resolveByVisionLessOcr(projectId, diagnosis);
        }
        TaskScreenshotView shot = lastUsableScreenshot(diagnosis.executionId());
        if (shot == null) {
            return FailureScreenshotEvidence.absent("该执行记录（#" + diagnosis.executionId()
                    + "）没有可用的失败现场截图产物，本次回答仅基于失败详情文本。");
        }
        try {
            // Download once through the presigned url, then inline as a base64
            // data url — the provider cannot reach the storage host itself.
            int maxBytes = SysConfigInts.positiveInt(
                    coreGateway.getSysConfig(KEY_MAX_BYTES), DEFAULT_MAX_BYTES);
            byte[] image = bytesFetcher.fetch(shot.url(), maxBytes);
            String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(image);
            LlmChatHandle handle = llmFallbackChain.chatHandle(
                    ChatCompletionRequest.of(model, promptTemplate.messages(
                            behaviorDirectivesHead(), diagnosis.errorDetail(), dataUrl)), projectId, null);
            aiCallLogWriter.insert(projectId, handle.result().provider(), handle.result().model(),
                    FUNCTION_NAME, handle.result().promptTokens(),
                    handle.result().completionTokens(), handle.result().latencyMs());
            String scene = handle.result().content() == null
                    ? "" : handle.result().content().strip();
            if (scene.isEmpty()) {
                return FailureScreenshotEvidence.absent("视觉模型对截图未给出可读描述，"
                        + "本次回答仅基于失败详情文本。");
            }
            log.info("Failure screenshot reading done projectId={} executionId={} model={}",
                    projectId, diagnosis.executionId(), handle.result().model());
            return FailureScreenshotEvidence.attached(bound(scene), shot.objectKey());
        } catch (RuntimeException failure) {
            // Enhancement leg only: the text-only answer proceeds with one honest line.
            log.warn("Failure screenshot reading degraded executionId={} err={}",
                    diagnosis.executionId(), failure.getMessage());
            return FailureScreenshotEvidence.absent("失败现场截图读取未完成（"
                    + failure.getMessage() + "），本次回答仅基于失败详情文本。");
        }
    }

    /**
 * Vision-less scene read : the screenshot
 * bytes are fetched once, transcribed by the shared {@link
 * OcrTextFallbackReader} against the hot-tunable density floor, and only
 * a text-dense verdict rides the scene block — marker pair plus the
 * honest transcription notice. Sparse or engine-failed = one honest
 * degraded line; zero model rounds, zero call_log rows.
 */
    private FailureScreenshotEvidence resolveByVisionLessOcr(long projectId,
                                                             FailureDiagnosis diagnosis) {
        TaskScreenshotView shot = lastUsableScreenshot(diagnosis.executionId());
        if (shot == null) {
            return FailureScreenshotEvidence.absent("该执行记录（#" + diagnosis.executionId()
                    + "）没有可用的失败现场截图产物，本次回答仅基于失败详情文本。");
        }
        try {
            int maxBytes = SysConfigInts.positiveInt(
                    coreGateway.getSysConfig(KEY_MAX_BYTES), DEFAULT_MAX_BYTES);
            int minLines = SysConfigInts.positiveInt(
                    coreGateway.getSysConfig(KEY_OCR_LINE_MIN), DEFAULT_MIN_LINES);
            byte[] image = bytesFetcher.fetch(shot.url(), maxBytes);
            OcrFallbackOutcome outcome = fallbackReader.read(image, minLines);
            if (outcome == null) {
                throw new IllegalStateException("OCR fallback reading returned nothing");
            }
            if (!outcome.textDense()) {
                String why = outcome.failureReason() != null
                        ? outcome.failureReason()
                        : OcrTextFallbackReader.sparseReason(outcome.lineCount());
                return FailureScreenshotEvidence.absent("失败现场截图 OCR 降级读取未完成（" + why
                        + "）；视觉模型未配置（系统设置-" + KEY_MODEL
                        + "），配置后自动改走图像理解；本次回答仅基于失败详情文本。");
            }
            String scene = OcrTextFallbackReader.degradedNotice(KEY_MODEL) + "\n"
                    + OcrTextFallbackReader.boundTranscription(outcome.derivedText());
            log.info("Failure screenshot OCR fallback reading done projectId={} executionId={} lines={}",
                    projectId, diagnosis.executionId(), outcome.lineCount());
            return FailureScreenshotEvidence.attached(scene, shot.objectKey());
        } catch (RuntimeException failure) {
            log.warn("Failure screenshot OCR fallback reading error executionId={} err={}",
                    diagnosis.executionId(), failure.getMessage());
            return FailureScreenshotEvidence.absent("失败现场截图 OCR 降级读取未完成（"
                    + failure.getMessage() + "）；视觉模型未配置（系统设置-" + KEY_MODEL
                    + "），本次回答仅基于失败详情文本。");
        }
    }

    /**
     * Last screenshot of the task = the failure scene (executor uploads per
     * step, the tail lands where the run died). A door error or an empty list
     * returns null so the caller degrades with the reason. One door read per
     * turn: the row carries both the presigned url and the trace anchor.
     */
    private TaskScreenshotView lastUsableScreenshot(long executionId) {
        List<TaskScreenshotView> shots;
        try {
            shots = coreGateway.taskScreenshots(executionId);
        } catch (RuntimeException doorFailure) {
            log.warn("Failure screenshot list reading failed executionId={} err={}",
                    executionId, doorFailure.getMessage());
            return null;
        }
        for (int i = shots.size() - 1; i >= 0; i--) {
            TaskScreenshotView shot = shots.get(i);
            if (shot.url() != null && !shot.url().isBlank()) {
                return shot;
            }
        }
        return null;
    }

    private static String bound(String raw) {
        return raw.length() <= SCENE_MAX_CHARS ? raw : raw.substring(0, SCENE_MAX_CHARS) + "…";
    }
}
