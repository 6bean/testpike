package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessVerdict;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides whether the pass-through door may serve one cataloged endpoint.
 *
 * <p>Order matters and is deliberate: the master switch first (one documented kill path), then
 * shape facts the catalog already knows (write class, unreadable body/header shape), then the
 * registered exclusions, then the governance overlay. Everything the catalog cannot judge for
 * itself fails closed — an endpoint without a project anchor stays unreachable until a governance
 * row says somebody verified it.
 */
@Component
public class ApiAccessPolicy {

    /** Master switch (K8 hot-updatable). Ships enabled: the door is a read door by default. */
    public static final String KEY_ENABLED = "ai.api-access.enabled";

    /**
     * Change-landing switch (K8 hot-updatable), shipped OFF.
     *
     * <p>Deliberate exception to "AI features ship enabled": a door that writes is a production
     * change surface, so it stays closed until an operator opens it on the configuration page. The
     * refusal always names this key and the page, so being refused is actionable rather than a wall.
     */
    public static final String KEY_WRITE_ENABLED = "ai.api-access.write.enabled";

    /** Whether change landings are opened at all (read path never consults this). */
    public boolean writeEnabled() {
        return configReader.getBoolean(KEY_WRITE_ENABLED, false);
    }

    /** Page where the switch lives, spelled the way the user has to find it. */
    private static final String SWITCH_PAGE = "系统配置页（/configs）";

    /**
     * Path prefixes deliberately outside the door. Reasons are registered instead of silent so a
     * reader of a rejection learns which surface is off-limits and why. The spellings are the ones
     * this service actually maps (a live catalog read, not an assumption): credential material,
     * machine tokens, configuration values, provider key rows, MCP handshake material, the object
     * transfer doors and the dialog's own surfaces.
     */
    private static final Map<String, String> EXCLUDED_PREFIXES = excludedPrefixes();

    private final SystemConfigReader configReader;

    public ApiAccessPolicy(SystemConfigReader configReader) {
        this.configReader = configReader;
    }

    /**
     * Whether a confirmed change can be composed from what the entry declares.
     *
     * <p>Two shapes are safe and two are not. An entry that takes no payload at all (a delete by
     * path id, an update whose only inputs are path and query anchors) is the most natural change
     * there is, so it composes — the first cut of this guard refused exactly that case. An entry
     * whose payload is declared as a type must declare at least one field, otherwise the only thing
     * the door could send is a body nobody whitelisted. And an entry the door cannot address at all
     * (a required header it has no way to set) never lands.
     */
    static boolean composable(ApiEndpointSpec spec) {
        if (spec.bodyType() == null) {
            return spec.dispatchable() || spec.unsupportedBodyOnly();
        }
        return !spec.bodyFields().isEmpty();
    }

    /** The plain-words half of a composition refusal, so the reader learns what to fix. */
    static String compositionProblem(ApiEndpointSpec spec) {
        if (spec.bodyType() != null) {
            return "声明的请求体 " + spec.bodyType() + " 没有可用字段白名单";
        }
        return "该条目要读请求头，直通无法提供：" + spec.unsupportedReason();
    }

    /** Whether the door is enabled at all (read per call, so the K8 switch takes effect hot). */
    public boolean enabled() {
        return configReader.getBoolean(KEY_ENABLED, true);
    }

    /**
     * Policy verdict for one cataloged endpoint.
     *
     * @param spec       catalog entry, null when the caller named something unknown
     * @param governance overlay rows by endpoint key (empty tolerated)
     */
    public ApiAccessVerdict decide(ApiEndpointSpec spec, Map<String, ApiAccessGovernanceView> governance) {
        return decide(spec, governance, false);
    }

    /**
     * Verdict for a landing attempt as well.
     *
     * <p>A change entry may only be served when the change switch is open, the entry was written
     * into the governance table as verified (opt-in per family: generation makes reads automatic,
     * it does not authorize writes), and the shape can actually be composed. Everything else keeps
     * the read verdict rules.
     */
    public ApiAccessVerdict decide(ApiEndpointSpec spec, Map<String, ApiAccessGovernanceView> governance,
                                   boolean changeLanding) {
        if (!enabled()) {
            return ApiAccessVerdict.deny("接口直通当前已关闭，可在" + SWITCH_PAGE
                    + "打开参数 " + KEY_ENABLED + "（即时生效）后重试");
        }
        if (spec == null) {
            return ApiAccessVerdict.deny("接口目录里没有这个条目，先查目录再调用（目录列出可读条目及其参数）");
        }
        if (spec.writeClass() && !changeLanding) {
            // Checked before the shape reason: for a change-class endpoint the caller's next move
            // is the draft path, not a reshaped body, so the actionable reason wins.
            return ApiAccessVerdict.deny("直读门只放行只读条目；" + spec.httpMethod() + " " + spec.pathPattern()
                    + " 属变更，变更走草案确认，不在此门");
        }
        if (changeLanding && !spec.writeClass()) {
            return ApiAccessVerdict.deny("变更落地门只执行变更条目；" + spec.key() + " 是只读条目，请用直读门");
        }
        if (changeLanding && !writeEnabled()) {
            return ApiAccessVerdict.deny("接口直通的变更落地当前已关闭，可在" + SWITCH_PAGE
                    + "打开参数 " + KEY_WRITE_ENABLED + "（即时生效）；关闭期间草案仍会生成，但确认后不会执行");
        }
        if (changeLanding && !composable(spec)) {
            return ApiAccessVerdict.deny("该条目无法安全组装载荷：" + spec.key()
                    + "（" + compositionProblem(spec) + "）");
        }
        if (!changeLanding && !spec.dispatchable()) {
            return ApiAccessVerdict.deny("该条目形状不适配直通：" + spec.unsupportedReason());
        }
        if (changeLanding) {
            ApiAccessGovernanceView verified = governance.get(spec.key());
            if (verified == null || !verified.scopeVerified()) {
                // Reader-facing: where to do the work instead, not which internal table is
                // missing a row. The endpoint key stays as the traceable anchor.
                return ApiAccessVerdict.deny("该变更接口暂未开放到对话，请在对应功能页面执行："
                        + spec.key());
            }
        }
        for (Map.Entry<String, String> excluded : EXCLUDED_PREFIXES.entrySet()) {
            if (covers(excluded.getKey(), spec.pathPattern())) {
                return ApiAccessVerdict.deny("该面不在直通可达集：" + excluded.getValue());
            }
        }
        ApiAccessGovernanceView row = governance.get(spec.key());
        if (row != null && !row.reachable()) {
            String registered = row.excludeReason() == null || row.excludeReason().isBlank()
                    ? "请在对应功能页面操作" : row.excludeReason();
            // Neutral prefix: a closed row may have been withdrawn or may have never been
            // misstate the second case to the reader.
            return ApiAccessVerdict.deny("该条目暂未开放到对话：" + registered);
        }
        if (!changeLanding && spec.projectAnchor() == null && spec.userAnchor() == null
                && (row == null || !row.scopeVerified())) {
            // Only genuinely new scopeless endpoints ever reach this wall now: the 11-06 batch
            // registered the whole catalog, so every known face answers with its own reason.
            return ApiAccessVerdict.deny("该查询的数据可见范围尚未与页面对齐，暂未开放到对话，"
                    + "请在对应功能页面查看：" + spec.key());
        }
        return ApiAccessVerdict.allow();
    }

    /**
     * Boundary-aware family match: the path must be the family itself or continue into a segment of
     * it. A plain {@code startsWith} would let the "storage" family swallow "storage-stats" and any
     * other neighbour that merely begins with the same letters.
     */
    static boolean covers(String familyPrefix, String pathPattern) {
        return pathPattern.equals(familyPrefix) || pathPattern.startsWith(familyPrefix + "/");
    }

    /** Registered exclusion families (insertion order kept so rejections read predictably). */
    private static Map<String, String> excludedPrefixes() {
        Map<String, String> prefixes = new LinkedHashMap<>();
        prefixes.put("/internal/credentials", "凭据库接口交付的是密文材料，不属于对话可代持的面");
        prefixes.put("/internal/api-tokens", "机器令牌面交付即凭证，代读等于把凭证交给对话上下文");
        prefixes.put("/internal/mcp-auth", "MCP 授权材料面（握手与密钥交换），不由对话代持");
        prefixes.put("/internal/configs", "系统配置值含引用型密钥（tool.auth-ref 族），值面不外交给对话");
        prefixes.put("/internal/llm-configs", "模型清单行含供应商密钥引用，不开放为可代调面");
        prefixes.put("/internal/storage", "对象存储走带签直传直下，签名即凭证，不由本门代持");
        prefixes.put("/storage/files", "对象存储走带签直传直下，签名即凭证，不由本门代持");
        prefixes.put("/internal/ai/chat", "对话自身接口，开放会造成对话套对话的递归");
        prefixes.put("/internal/ai-chat", "对话自身接口（草稿、图片、工具产物），开放会造成对话套对话的递归");
        return Map.copyOf(prefixes);
    }
}
