package com.sixbean.testpike.core.asset.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.UiActionContract;
import com.sixbean.testpike.core.credential.domain.component.CredPlaceholderInjector;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Payload contract of the shared login recipe (W5 app automation phase 2, segment B1).
 * <p>A published recipe is readable by every project that discovers it and, for the app
 * variant, its step sequence is replayed by the executor against a real device, so two
 * things have to be true before a row is stored: the steps must stay engine independent —
 * every action is an {@code app.*} verb of the abstract action IR, judged by the same
 * single source the record and natural-language step chains use
 * ({@link UiActionContract}) — and the payload must never carry a secret literal, only a
 * reference into the credential vault, judged by the same two dialects the dispatch
 * injector resolves ({@link CredPlaceholderInjector}), so no second grammar is introduced.
 * <p>Compatibility: payloads without a {@code target} discriminator are snapshots published
 * before this segment and keep their shape (the web-side recipe is executed by the monitor
 * login chain, whose own contract stays there). Once a recipe declares a variant, the
 * declared value is checked and the app variant is held to the step contract above.
 * <p>Fail-closed and secret-safe: a plaintext secret is named by its field path only — the
 * offending value is never echoed into the message, while a malformed reference is echoed
 * because it is a reference attempt, not a secret.
 * <p>Pure static gate (no database access, no Spring bean needed by either caller): the
 * publish chain calls it before its first SQL write, so a rejection can never poison the
 * surrounding transaction.
 */
public final class LoginRecipePayloadContract {

    /** Variant discriminator key of a recipe payload. */
    public static final String TARGET_KEY = "target";

    /** Web variant: browser cookie/form recipe consumed by the monitor login chain. */
    public static final String VARIANT_WEB = "web";

    /** App variant: engine independent {@code app.*} step sequence consumed by the executor. */
    public static final String VARIANT_APP = "app";

    /** Step sequence key of the app variant. */
    public static final String STEPS_KEY = "steps";

    private static final Set<String> VARIANTS = Set.of(VARIANT_WEB, VARIANT_APP);

    /** Field names that hold a secret; compared case-insensitively after stripping
     * separators, so password / Password / pass_word all match and nothing else does. */
    private static final Set<String> SECRET_FIELDS = Set.of(
            "password", "passwd", "pass", "passcode", "pincode", "secret", "secretkey",
            "token", "accesstoken", "refreshtoken", "authtoken", "apikey", "accesskey",
            "privatekey", "credential");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LoginRecipePayloadContract() {
        // static payload contract, never instantiated
    }

    /**
     * Validate a {@code login_recipe} payload.
     *
     * @param payloadJson the raw payload as submitted to the publish chain
     * @throws ServiceException 40001 with the offending position named, before any write
     */
    public static void requirePublishablePayload(String payloadJson) {
        JsonNode root = parse(payloadJson);
        if (!root.isObject()) {
            throw new ServiceException("登录配方载荷必须是一个 JSON 对象（形如 "
                    + "{\"target\":\"app\",\"steps\":[...]}），当前是"
                    + describe(root) + "——步骤片段请作为用例片段资产发布，配方只描述怎么登录；"
                    + "请到「共享资产池」按正确写法重新发布：{\"target\":\"app\",\"steps\":[…]}",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String variant = readVariant(root);
        requireSecretsUseVaultReferences(root, "payload", new IdentityHashMap<>());
        if (VARIANT_APP.equals(variant)) {
            requireAppSteps(root);
        }
    }

    /**
     * Resolve and extract the step sequence of an app-variant recipe payload.
     * <p>Applies the same gates as {@link #requirePublishablePayload(String)} and one
     * extra rule: the payload must declare {@code target="app"}. A web recipe or a
     * pre-B1 snapshot without a variant tag can not be replayed against a device, so
     * binding such a row to an env profile is refused instead of silently shipping
     * steps the executor would never run. The returned steps still carry credential
     * placeholders — resolution stays at the dispatch injection single point.
     */
    public static java.util.List<Map<String, Object>> appStepsOf(String payloadJson) {
        JsonNode root = parse(payloadJson);
        if (!root.isObject()) {
            throw new ServiceException("登录配方载荷必须是 JSON 对象，当前是"
                    + describe(root) + "——取不出可回放的步骤序列；"
                    + "请到「共享资产池」重新发布该配方，正确写法：{\"target\":\"app\",\"steps\":[…]}",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!VARIANT_APP.equals(readVariant(root))) {
            throw new ServiceException("环境档案只能绑定 APP 变体的登录配方（载荷需 \"target\":\"app\" "
                    + "并带 steps 步骤序列）——web 配方或无变体标注的旧快照不会被执行器在真机上回放，"
                    + "绑定它们不会生效；请到「项目环境」改选 APP 变体的登录配方后重新保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        requireSecretsUseVaultReferences(root, "payload", new IdentityHashMap<>());
        requireAppSteps(root);
        return MAPPER.convertValue(root.get(STEPS_KEY),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Map<String, Object>>>() { });
    }

    private static JsonNode parse(String payloadJson) {
        try {
            return MAPPER.readTree(payloadJson);
        } catch (Exception parseFailure) {
            throw new ServiceException("登录配方载荷不是合法 JSON：" + payloadShape(payloadJson)
                    + "（发布前需通过配方载荷校验，未通过不会写入资产库）——"
                    + "可在「共享资产池」参考现有配方的正确写法：{\"target\":\"app\",\"steps\":"
                    + "[{\"action\":\"app.launch\",\"params\":{...}}]}",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Only the shape of the input is echoed, never its content (a bad payload may be one
     * half-written secret literal and the message reaches every caller of the pool). */
    private static String payloadShape(String payloadJson) {
        return "无法解析（长度 " + payloadJson.trim().length() + " 个字符）";
    }

    private static String readVariant(JsonNode root) {
        JsonNode target = root.get(TARGET_KEY);
        if (target == null || target.isNull()) {
            return null;
        }
        if (!target.isTextual()) {
            throw variantRejection(String.valueOf(target));
        }
        String variant = target.asText().trim().toLowerCase(Locale.ROOT);
        if (!VARIANTS.contains(variant)) {
            throw variantRejection(target.asText());
        }
        return variant;
    }

    private static ServiceException variantRejection(String submitted) {
        return new ServiceException("登录配方载荷的 target（运行端）只支持 "
                + String.join(" / ", List.of(VARIANT_WEB, VARIANT_APP))
                + "，当前是「" + submitted + "」——请把 target 改成 web（浏览器登录配方）"
                + "或 app（APP 登录配方），正确写法：\"target\":\"app\""
                + "（不填 target 视为旧版配方快照，保持原样可发布）",
                BizErrorCode.VALIDATION_ERROR);
    }

    /**
     * App variant step sequence: a non-empty array of steps, each carrying an {@code app.*}
     * action verb of the IR baseline. Verb legality is delegated to {@link UiActionContract}
     * (the same table the record and natural-language chains gate on) — no second verb list
     * here; engine capability pruning stays at dispatch, where the target engine is known.
     */
    private static void requireAppSteps(JsonNode root) {
        JsonNode steps = root.get(STEPS_KEY);
        if (steps == null || steps.isNull()) {
            throw new ServiceException("APP 登录配方必须给出 steps 步骤序列"
                    + "（target=app 时步骤是这份配方的全部内容，空配方不会被执行器接受）"
                    + "——请在「共享资产池」编辑载荷补上 steps，正确写法："
                    + "\"steps\":[{\"action\":\"app.launch\",\"params\":{...}}]",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!steps.isArray()) {
            throw new ServiceException("APP 登录配方的 steps 必须是步骤数组，当前是"
                    + describe(steps) + "——请把 steps 改成数组，正确写法："
                    + "\"steps\":[{\"action\":\"app.launch\",\"params\":{...}}]",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (steps.isEmpty()) {
            throw new ServiceException("APP 登录配方的 steps 不能是空数组"
                    + "——至少要有一步（如 app.launch 打开应用、app.tap 点登录入口），"
                    + "请在「共享资产池」补齐步骤后重新发布",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int position = 0;
        for (JsonNode step : steps) {
            position++;
            requireStep(step, position);
        }
    }

    private static void requireStep(JsonNode step, int position) {
        if (!step.isObject()) {
            throw new ServiceException("APP 登录配方第 " + position + " 步必须是 JSON 对象"
                    + "（形如 {\"action\":\"app.tap\",\"params\":{...}}），当前是"
                    + describe(step) + "——请把该步改成对象，正确写法："
                    + "{\"action\":\"app.tap\",\"params\":{...}}",
                    BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode action = step.get("action");
        if (action == null || !action.isTextual() || action.asText().isBlank()) {
            throw new ServiceException("APP 登录配方第 " + position + " 步缺少 action（动作名）"
                    + "——请补上 action 字段写明执行哪个动作，正确写法：\"action\":\"app.tap\"",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String actionName = action.asText().trim();
        if (!UiActionContract.isAppAction(actionName)) {
            throw new ServiceException("APP 登录配方第 " + position + " 步「" + actionName
                    + "」不是 app. 前缀的动作——APP 配方只走引擎无关的动作表"
                    + "（换执行引擎用例零改动），请改成 app. 前缀的动作，"
                    + "正确写法：\"action\":\"app.tap\"",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String violation = UiActionContract.appActionViolation(actionName);
        if (violation != null) {
            throw new ServiceException("APP 登录配方第 " + position + " 步被拒：" + violation
                    + "——请按动作表要求修正该步后在「共享资产池」重新发布",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * Walk the whole payload: a field whose name holds a secret must carry a vault
     * reference in one of the two dialects the injector resolves.
     * <p>Identity-keyed visited set: Jackson reuses node instances for shared sub-trees,
     * and a reference cycle (hand-built node) must not loop forever inside a request.
     */
    private static void requireSecretsUseVaultReferences(JsonNode node, String path,
                                                          Map<JsonNode, Boolean> visiting) {
        if (node == null) {
            return;
        }
        if (visiting.put(node, Boolean.TRUE) != null) {
            return;
        }
        try {
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String fieldPath = path + "." + field.getKey();
                    if (isSecretField(field.getKey())) {
                        requireSecretValue(field.getValue(), fieldPath);
                    }
                    requireSecretsUseVaultReferences(field.getValue(), fieldPath, visiting);
                }
                return;
            }
            if (node.isArray()) {
                int index = 0;
                for (JsonNode item : node) {
                    requireSecretsUseVaultReferences(item, path + "[" + index + "]", visiting);
                    index++;
                }
            }
        } finally {
            visiting.remove(node);
        }
    }

    private static void requireSecretValue(JsonNode value, String fieldPath) {
        String text = value != null && value.isTextual() ? value.asText() : null;
        if (text != null && CredPlaceholderInjector.isCredentialReference(text)) {
            return;
        }
        if (text != null && CredPlaceholderInjector.looksLikeCredentialReference(text)) {
            // A reference attempt that the injector can never resolve: naming the value is
            // safe here (it is a reference, not a secret) and is what makes the fix obvious.
            throw new ServiceException("登录配方字段「" + bareField(fieldPath)
                    + "」的凭据引用「" + text + "」写法不正确——引用键只能是字母或下划线开头的"
                    + "标识符（不含点号），请改成正确写法：{{CRED:引用键}} 或 @cred/引用键"
                    + "（可先在「应用自动化 → 凭据保险箱」查该凭据的引用键）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        throw new ServiceException("登录配方字段「" + bareField(fieldPath) + "」保存了明文凭据值"
                + "（共享资产池的载荷对所有可见项目开放读取，明文口令会随之扩散，"
                + "值本身不在此回显）——"
                + "请到「应用自动化 → 凭据保险箱」新建凭据，步骤里只写引用："
                + "{{CRED:引用键}} 或 @cred/引用键"
                + "（保险箱实值在派发前由服务端注入，配方本身不保存口令，"
                + "发布到共享资产池后所有可见项目都能读到这份载荷）",
                BizErrorCode.VALIDATION_ERROR);
    }

    private static boolean isSecretField(String key) {
        return key != null && SECRET_FIELDS.contains(normalize(key));
    }

    private static String normalize(String key) {
        StringBuilder out = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /** The leaf field name only — the path prefix stays in the log, not in the message. */
    private static String bareField(String fieldPath) {
        int dot = fieldPath.lastIndexOf('.');
        return dot < 0 ? fieldPath : fieldPath.substring(dot + 1);
    }

    private static String describe(JsonNode node) {
        if (node == null) {
            return "缺失";
        }
        if (node.isObject()) {
            return "JSON 对象";
        }
        if (node.isArray()) {
            return "JSON 数组";
        }
        if (node.isTextual()) {
            return "一段文本";
        }
        if (node.isNull()) {
            return "null";
        }
        return node.isBoolean() ? "true/false" : "一个数字";
    }
}
