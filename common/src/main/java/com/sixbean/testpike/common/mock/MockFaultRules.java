package com.sixbean.testpike.common.mock;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Fault-injection parameter-domain rules — the one spectrum the save chain
 * (core MockRuleValidator) and the data plane (mock-server MockFaultInjector)
 * both consume, so a half-configured fault is blocked before rows land and an
 * older row still fails closed at serve time (validation lives in one place,
 * never a second copy downstream).
 * <p>Stub-level faults carry their own optional probability
 * gate; the space chaos slider is a separate layer and its label deliberately
 * stays out of the stub spectrum (a stub must not pretend to be chaos).
 */
public final class MockFaultRules {

    /** Fault types the stub spectrum accepts — injector and validators share it. */
    public static final List<String> FAULT_TYPES =
            List.of("delay", "disconnect", "status", "malformed", "dribble");

    /** Default status-override when a status fault omits statusCode. */
    public static final int DEFAULT_FAULT_STATUS = 503;

    /** Default chaos-injected status: service unavailable, retry-friendly. */
    public static final int CHAOS_STATUS = 503;

    private MockFaultRules() {
    }


    /**
     * Validate the {@code fault} key of a response-builder document.
     *
     * @param fault        the fault node (null/missing = no fault, always legal)
     * @param responseRoot the whole builder document (contentType decides the
     *                     malformed fault's body-shape requirement)
     * @return a plain-word violation message, or null when the fault is legal
     */
    public static String faultViolation(JsonNode fault, JsonNode responseRoot) {
        if (fault == null || fault.isNull()) {
            return null;
        }
        if (!fault.isObject()) {
            return "故障注入（fault）必须是 JSON 对象（type 及该类型的参数键）";
        }
        String type = fault.path("type").asText("");
        if (type.isEmpty()) {
            return "故障注入缺少 type 键，可用：" + String.join(" / ", FAULT_TYPES);
        }
        if (!FAULT_TYPES.contains(type)) {
            return "故障注入类型「" + type + "」不在允许谱内，可用："
                    + String.join(" / ", FAULT_TYPES);
        }
        String gateViolation = probabilityGateViolation(fault);
        if (gateViolation != null) {
            return gateViolation;
        }
        return switch (type) {
            case "delay" -> delayViolation(fault);
            case "disconnect" -> null;
            case "status" -> statusViolation(fault);
            case "malformed" -> servesJsonBody(responseRoot) ? null
                    : "畸形 JSON 故障要求响应体为 JSON 形态"
                            + "（contentType 非 json 的 stub 请改用 status/delay/disconnect 故障）";
            case "dribble" -> dribbleViolation(fault);
            default -> "故障类型「" + type + "」无对应参数域规则";
        };
    }

    /** Probability gate: missing key = always; outside 0-100 = loud refuse. */
    private static String probabilityGateViolation(JsonNode fault) {
        if (!fault.hasNonNull("probability")) {
            return null;
        }
        double probability = fault.path("probability").asDouble(-1);
        if (probability < 0 || probability > 100) {
            return "故障注入概率 probability=" + fault.path("probability").asText()
                    + " 超出 0-100 范围";
        }
        return null;
    }

    private static String delayViolation(JsonNode fault) {
        int min = fault.path("minMs").asInt(0);
        int max = fault.path("maxMs").asInt(min);
        if (min < 0 || max < min) {
            return "延迟故障区间非法：minMs=" + min + " maxMs=" + max
                    + "（需 0 ≤ minMs ≤ maxMs）";
        }
        return null;
    }

    private static String statusViolation(JsonNode fault) {
        int status = fault.path("statusCode").asInt(DEFAULT_FAULT_STATUS);
        if (status < 500 || status > 599) {
            return "状态故障 statusCode=" + status
                    + " 必须是 5xx（伪造业务码不是故障注入的职责）";
        }
        return null;
    }

    private static String dribbleViolation(JsonNode fault) {
        int chunkBytes = fault.path("chunkBytes").asInt(64);
        int chunkDelayMs = fault.path("chunkDelayMs").asInt(100);
        if (chunkBytes <= 0 || chunkDelayMs < 0) {
            return "慢速 dribble 参数非法：chunkBytes=" + chunkBytes
                    + " chunkDelayMs=" + chunkDelayMs
                    + "（需 chunkBytes>0 且 chunkDelayMs≥0）";
        }
        return null;
    }

    /**
     * Malformed-JSON fault only makes sense on a JSON-shaped body: the builder
     * document may declare {@code contentType} (default = JSON since the form
     * builder's happy path is JSON APIs).
     */
    public static boolean servesJsonBody(JsonNode responseRoot) {
        String contentType = responseRoot == null
                ? "" : responseRoot.path("contentType").asText("");
        return contentType.isEmpty() || contentType.contains("json");
    }
}
