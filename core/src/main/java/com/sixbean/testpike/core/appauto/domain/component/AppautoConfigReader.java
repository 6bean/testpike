package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Component
public class AppautoConfigReader {

    private static final Logger log = LoggerFactory.getLogger(AppautoConfigReader.class);

 
    public static final String KEY_DEFAULT_ENGINE = "appauto.default-engine";

 
    public static final String KEY_VISION_MASTER = "ai.vision.enabled";

 
    public static final String KEY_VISION_APPAUTO_LOCATE = "ai.vision.appauto-locate";

 
    public static final String KEY_LOCATE_CONFIDENCE = "ai.appauto.locate.confidence";

 
    public static final String KEY_FLAKY_SENTINEL = "ai.appauto.flaky-sentinel";

 
    public static final String KEY_STEP_HEALING = "ai.appauto.step-healing";

 
    public static final String KEY_NL_STEP = "ai.appauto.nl-step";

 
    public static final String KEY_RECORD_TO_FLOW = "ai.appauto.record-to-flow";

 
    public static final String KEY_WX_MOCK = "ai.appauto.wx-mock";

 
    public static final String KEY_POPUP_RULE_LEARN = "ai.appauto.popup-rule-learn";

 
    public static final String KEY_SUPPORT_WINDOW_DAYS = "appauto.support-health-window-days";

 
    public static final String KEY_SUPPORT_PASS_RATE_MIN = "appauto.support-health-pass-rate-min";

 
    public static final String KEY_UPGRADE_ADVISOR = "ai.appauto.upgrade-advisor";

 
    public static final String KEY_UPGRADE_WINDOW = "ai.appauto.upgrade-window";

 
    public static final String KEY_UPGRADE_WINDOW_HOURS = "appauto.upgrade-window-hours";

 
    public static final String KEY_UPGRADE_FLAKY_WINDOW_HOURS = "appauto.upgrade-flaky-window-hours";

 
    public static final String KEY_UPGRADE_FLAKY_DROP_PCT = "appauto.upgrade-flaky-drop-pct";

 
    public static final String KEY_MATRIX_FANOUT_MAX = "appauto.matrix-fanout-max";

 
    public static final String KEY_PKG_MAX_SIZE = "appauto.pkg-max-size";

 
    private static final long DEFAULT_PKG_MAX_SIZE = 104_857_600L;

 
    public static final String KEY_MATRIX_SAMPLE = "ai.appauto.matrix-sample";

 
    public static final String KEY_DEVICE_TEMP_MAX = "appauto.device-temp-max";

 
    public static final String KEY_DEVICE_HOT_WINDOW_MINUTES = "appauto.device-hot-window-minutes";

 
    public static final String KEY_PERF_SAMPLE_INTERVAL = "appauto.perf-sample-interval";

 
    public static final String KEY_DEVICE_CPU_FULL_PCT = "appauto.device-cpu-full-pct";

 
    public static final String KEY_DEVICE_SEEN_WINDOW_SECONDS = "appauto.device-seen-window-seconds";

 
    public static final String KEY_TAKEOVER_REQUEST_TTL_SECONDS = "appauto.takeover.request-ttl-seconds";

 
    public static final String KEY_TAKEOVER_INPUT_TTL_SECONDS = "appauto.takeover.input-ttl-seconds";

 
    public static final String KEY_TAKEOVER_INPUT_QUEUE_MAX = "appauto.takeover.input-queue-max";

 
    public static final String KEY_DEVICE_PROBE_TIMEOUT_MS = "appauto.device-probe-timeout-ms";

 
    public static final String KEY_RESERVATION_APPROVAL = "appauto.reservation.approval-required";

 
    public static final String KEY_RESERVATION_WINDOW_OVERRUN_MINUTES =
            "appauto.reservation.window-overrun-minutes";

    /** Hot key capping how many approved popup rules a dispatch envelope carries. */
    public static final String KEY_POPUP_RULE_ENVELOPE_LIMIT =
            "appauto.popup-rule-envelope-limit";

    /**
     * Hot key listing the scale factors applied when the operator captures an
     * image element from a live frame without choosing scales explicitly
     * (comma-separated decimal list, read per call so it stays tunable).
     */
    public static final String KEY_ELEMENT_CAPTURE_DEFAULT_SCALES =
            "appauto.element.capture-default-scales";

    /** Hot key capping how many scaled variants one capture call may produce. */
    public static final String KEY_ELEMENT_CAPTURE_MAX_VARIANTS =
            "appauto.element.capture-max-variants";

    /** Default scale list (matches the seeded config value; includes 1.0 = untouched crop). */
    static final String DEFAULT_ELEMENT_CAPTURE_SCALES = "1.0,0.8,1.25";

    /** Default per-capture variant ceiling (generous for real template sets). */
    static final int DEFAULT_ELEMENT_CAPTURE_MAX_VARIANTS = 6;

    /** Hot key for the device-wall thumbnail target width in pixels. */
    public static final String KEY_WALL_THUMB_WIDTH_PX =
            "appauto.wall.thumb-width-px";

    /** Hot key for how long one assembled wall thumbnail stays cached. */
    public static final String KEY_WALL_THUMB_TTL_SECONDS =
            "appauto.wall.thumb-ttl-seconds";

    /** Shipped wall thumbnail width (matches the seeded config value). */
    static final int DEFAULT_WALL_THUMB_WIDTH_PX = 240;

    /** Shipped wall thumbnail cache window (short: stale cells must fade fast). */
    static final int DEFAULT_WALL_THUMB_TTL_SECONDS = 3;

 
    private static final int DEFAULT_DEVICE_TEMP_MAX = 42;
    private static final int DEFAULT_DEVICE_HOT_WINDOW_MINUTES = 10;
    private static final int DEFAULT_PERF_SAMPLE_INTERVAL = 1;
 
    private static final int DEFAULT_DEVICE_CPU_FULL_PCT = 95;

 
    private static final int DEFAULT_DEVICE_SEEN_WINDOW_SECONDS = 300;

 
    private static final int DEFAULT_DEVICE_PROBE_TIMEOUT_MS = 2000;

 
    private static final int DEFAULT_TAKEOVER_REQUEST_TTL_SECONDS = 300;

 
    private static final int DEFAULT_INPUT_TTL_SECONDS = 60;

 
    private static final int DEFAULT_INPUT_QUEUE_MAX = 50;

 
    private static final int DEFAULT_MATRIX_FANOUT_MAX = 12;

    /** Fallback when the popup-rule stamping key is absent (equals the seeded value). */
    private static final int DEFAULT_POPUP_RULE_ENVELOPE_LIMIT = 50;

 
    private static final int DEFAULT_SUPPORT_WINDOW_DAYS = 7;
    private static final int DEFAULT_SUPPORT_PASS_RATE_MIN = 80;

 
    private static final int DEFAULT_UPGRADE_WINDOW_HOURS = 24;
    private static final int DEFAULT_UPGRADE_FLAKY_WINDOW_HOURS = 24;
    private static final int DEFAULT_UPGRADE_FLAKY_DROP_PCT = 30;

    
    private static final String GATE_OFF = "0";
    private static final String CONFIDENCE_DEFAULT = "0.85,0.6";

    
    static final String BUILTIN_DEFAULT = "MOBILEWRIGHT";

    private final ConfigService configService;
    private final AppautoEngineService engineService;

    public AppautoConfigReader(ConfigService configService, AppautoEngineService engineService) {
        this.configService = configService;
        this.engineService = engineService;
    }

    
    public String defaultEngine() {
        String raw = configService
                .getValue(KEY_DEFAULT_ENGINE, BUILTIN_DEFAULT)
                .trim().toUpperCase(Locale.ROOT);
        Set<String> enabledCodes = engineService.listEngines(false).stream()
                .map(e -> e.code().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return enabledCodes.contains(raw) ? raw : BUILTIN_DEFAULT;
    }

    
    public Map<String, String> visualFallbackKeys() {
        Map<String, String> projection = new LinkedHashMap<>();
        projection.put(KEY_VISION_MASTER,
                configService.getValue(KEY_VISION_MASTER, GATE_OFF));
        projection.put(KEY_VISION_APPAUTO_LOCATE,
                configService.getValue(KEY_VISION_APPAUTO_LOCATE, GATE_OFF));
        projection.put(KEY_LOCATE_CONFIDENCE,
                configService.getValue(KEY_LOCATE_CONFIDENCE, CONFIDENCE_DEFAULT));
        return projection;
    }

    
    public boolean flakySentinelEnabled() {
        return "1".equals(configService.getValue(KEY_FLAKY_SENTINEL, GATE_OFF).trim());
    }

    
    public boolean stepHealingEnabled() {
        return "1".equals(configService.getValue(KEY_STEP_HEALING, GATE_OFF).trim());
    }

    
    public boolean nlStepEnabled() {
        return "1".equals(configService.getValue(KEY_NL_STEP, GATE_OFF).trim());
    }

    
    public boolean recordToFlowEnabled() {
        return "1".equals(configService.getValue(KEY_RECORD_TO_FLOW, GATE_OFF).trim());
    }

    
    public boolean wxMockEnabled() {
        return "1".equals(configService.getValue(KEY_WX_MOCK, GATE_OFF).trim());
    }

    
    public boolean popupRuleLearnEnabled() {
        return "1".equals(configService.getValue(KEY_POPUP_RULE_LEARN, GATE_OFF).trim());
    }

    
    public int supportWindowDays() {
        return positiveIntConfig(KEY_SUPPORT_WINDOW_DAYS, DEFAULT_SUPPORT_WINDOW_DAYS);
    }

    
    public int supportPassRateMin() {
        return positiveIntConfig(KEY_SUPPORT_PASS_RATE_MIN, DEFAULT_SUPPORT_PASS_RATE_MIN);
    }

    
    public boolean upgradeAdvisorEnabled() {
        return "1".equals(configService.getValue(KEY_UPGRADE_ADVISOR, GATE_OFF).trim());
    }

    
    public boolean matrixSampleEnabled() {
        return "1".equals(configService.getValue(KEY_MATRIX_SAMPLE, GATE_OFF).trim());
    }

 
    public boolean upgradeWindowEnabled() {
        return "1".equals(configService.getValue(KEY_UPGRADE_WINDOW, GATE_OFF).trim());
    }

    
    public int upgradeWindowHours() {
        return positiveIntConfig(KEY_UPGRADE_WINDOW_HOURS, DEFAULT_UPGRADE_WINDOW_HOURS);
    }

    
    public int upgradeFlakyWindowHours() {
        return positiveIntConfig(KEY_UPGRADE_FLAKY_WINDOW_HOURS,
                DEFAULT_UPGRADE_FLAKY_WINDOW_HOURS);
    }

    
    public int upgradeFlakyDropPct() {
        return positiveIntConfig(KEY_UPGRADE_FLAKY_DROP_PCT, DEFAULT_UPGRADE_FLAKY_DROP_PCT);
    }

    
    public int matrixFanoutMax() {
        return positiveIntConfig(KEY_MATRIX_FANOUT_MAX, DEFAULT_MATRIX_FANOUT_MAX);
    }

    /**
     * Approved popup rules stamped into one dispatch envelope (hot key
     * appauto.popup-rule-envelope-limit, seeded default 50 — generous enough for
     * real rule libraries, read per call so the value can be tuned at runtime).
     */
    public int popupRuleEnvelopeLimit() {
        return positiveIntConfig(KEY_POPUP_RULE_ENVELOPE_LIMIT, DEFAULT_POPUP_RULE_ENVELOPE_LIMIT);
    }

    /**
     * Default scale list for live-frame element capture (hot key
     * appauto.element.capture-default-scales, seeded "1.0,0.8,1.25"). Malformed
     * items are skipped with a warning; an all-invalid or blank value falls back
     * to the shipped list so the capture path never silently produces zero variants.
     */
    public List<Double> elementCaptureDefaultScales() {
        String raw = configService.getValue(KEY_ELEMENT_CAPTURE_DEFAULT_SCALES,
                DEFAULT_ELEMENT_CAPTURE_SCALES);
        List<Double> scales = new ArrayList<>();
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                double parsed = Double.parseDouble(token);
                if (parsed > 0) {
                    scales.add(parsed);
                }
            } catch (NumberFormatException e) {
                log.warn("配置键 {} 含非法倍率「{}」，该项跳过", KEY_ELEMENT_CAPTURE_DEFAULT_SCALES, token);
            }
        }
        if (scales.isEmpty()) {
            log.warn("配置键 {} 解析后为空，回退出厂倍率集 {}",
                    KEY_ELEMENT_CAPTURE_DEFAULT_SCALES, DEFAULT_ELEMENT_CAPTURE_SCALES);
            scales = elementCaptureParseScales(DEFAULT_ELEMENT_CAPTURE_SCALES);
        }
        return scales;
    }

    /** Per-capture variant ceiling (hot key appauto.element.capture-max-variants, seeded 6). */
    public int elementCaptureMaxVariants() {
        int parsed = positiveIntConfig(KEY_ELEMENT_CAPTURE_MAX_VARIANTS,
                DEFAULT_ELEMENT_CAPTURE_MAX_VARIANTS);
        return parsed > 0 ? parsed : DEFAULT_ELEMENT_CAPTURE_MAX_VARIANTS;
    }

    /**
     * Device-wall thumbnail target width in pixels (hot key
     * appauto.wall.thumb-width-px, seeded 240). Frames narrower than the width
     * are still downscaled to the same face; the wall cell trusts this value.
     */
    public int wallThumbWidthPx() {
        int parsed = positiveIntConfig(KEY_WALL_THUMB_WIDTH_PX, DEFAULT_WALL_THUMB_WIDTH_PX);
        return parsed > 0 ? parsed : DEFAULT_WALL_THUMB_WIDTH_PX;
    }

    /** Wall thumbnail cache window seconds (hot key appauto.wall.thumb-ttl-seconds, seeded 3). */
    public int wallThumbTtlSeconds() {
        int parsed = positiveIntConfig(KEY_WALL_THUMB_TTL_SECONDS, DEFAULT_WALL_THUMB_TTL_SECONDS);
        return parsed > 0 ? parsed : DEFAULT_WALL_THUMB_TTL_SECONDS;
    }

    private static List<Double> elementCaptureParseScales(String csv) {
        List<Double> scales = new ArrayList<>();
        for (String part : csv.split(",")) {
            String token = part.trim();
            if (!token.isEmpty()) {
                scales.add(Double.parseDouble(token));
            }
        }
        return scales;
    }

    
    public long pkgMaxSizeBytes() {
        String raw = configService.getValue(KEY_PKG_MAX_SIZE, null);
        if (raw != null && !raw.isBlank()) {
            try {
                long parsed = Long.parseLong(raw.trim());
                if (parsed >= 0) {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                log.warn("配置键 {} 非法值「{}」，回退保守默认 {}", KEY_PKG_MAX_SIZE, raw, DEFAULT_PKG_MAX_SIZE);
            }
        }
        return DEFAULT_PKG_MAX_SIZE;
    }

    
    public Map<String, String> perfKeys() {
        Map<String, String> projection = new LinkedHashMap<>();
        projection.put(KEY_PERF_SAMPLE_INTERVAL,
                String.valueOf(positiveIntConfig(KEY_PERF_SAMPLE_INTERVAL, DEFAULT_PERF_SAMPLE_INTERVAL)));
        projection.put(KEY_DEVICE_TEMP_MAX,
                String.valueOf(deviceTempMaxC()));
        return projection;
    }

    
    public int deviceTempMaxC() {
        int parsed = positiveIntConfig(KEY_DEVICE_TEMP_MAX, DEFAULT_DEVICE_TEMP_MAX);
        return parsed > 0 ? parsed : DEFAULT_DEVICE_TEMP_MAX;
    }

    
    public int deviceHotWindowMinutes() {
        int parsed = positiveIntConfig(KEY_DEVICE_HOT_WINDOW_MINUTES, DEFAULT_DEVICE_HOT_WINDOW_MINUTES);
        return parsed > 0 ? parsed : DEFAULT_DEVICE_HOT_WINDOW_MINUTES;
    }

 
    public int deviceCpuFullPct() {
        int parsed = positiveIntConfig(KEY_DEVICE_CPU_FULL_PCT, DEFAULT_DEVICE_CPU_FULL_PCT);
        return parsed > 0 ? parsed : DEFAULT_DEVICE_CPU_FULL_PCT;
    }

 
    public int deviceSeenWindowSeconds() {
        int parsed = positiveIntConfig(KEY_DEVICE_SEEN_WINDOW_SECONDS,
                DEFAULT_DEVICE_SEEN_WINDOW_SECONDS);
        return parsed > 0 ? parsed : DEFAULT_DEVICE_SEEN_WINDOW_SECONDS;
    }

 
    public int deviceProbeTimeoutMs() {
        int parsed = positiveIntConfig(KEY_DEVICE_PROBE_TIMEOUT_MS,
                DEFAULT_DEVICE_PROBE_TIMEOUT_MS);
        return parsed > 0 ? parsed : DEFAULT_DEVICE_PROBE_TIMEOUT_MS;
    }

 
    public int takeoverRequestTtlSeconds() {
        int parsed = positiveIntConfig(KEY_TAKEOVER_REQUEST_TTL_SECONDS,
                DEFAULT_TAKEOVER_REQUEST_TTL_SECONDS);
        return parsed > 0 ? parsed : DEFAULT_TAKEOVER_REQUEST_TTL_SECONDS;
    }

 
    public int takeoverInputTtlSeconds() {
        int parsed = positiveIntConfig(KEY_TAKEOVER_INPUT_TTL_SECONDS, DEFAULT_INPUT_TTL_SECONDS);
        return parsed > 0 ? parsed : DEFAULT_INPUT_TTL_SECONDS;
    }

 
    public int takeoverInputQueueMax() {
        int parsed = positiveIntConfig(KEY_TAKEOVER_INPUT_QUEUE_MAX, DEFAULT_INPUT_QUEUE_MAX);
        return parsed > 0 ? parsed : DEFAULT_INPUT_QUEUE_MAX;
    }

    
    public boolean reservationApprovalRequired() {
        return "1".equals(configService.getValue(KEY_RESERVATION_APPROVAL, GATE_OFF).trim());
    }

 
    public int reservationOverrunGraceMinutes() {
        return positiveIntConfig(KEY_RESERVATION_WINDOW_OVERRUN_MINUTES, 0);
    }

    
    private int positiveIntConfig(String key, int fallback) {
        String raw = configService.getValue(key, null);
        if (raw != null && !raw.isBlank()) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed >= 0) {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                log.warn("配置键 {} 非法值「{}」，回退保守默认 {}", key, raw, fallback);
            }
        }
        return fallback;
    }
}
