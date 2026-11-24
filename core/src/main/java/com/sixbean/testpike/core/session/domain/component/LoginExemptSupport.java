package com.sixbean.testpike.core.session.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.project.domain.component.EnvParamCipherSupport;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInfo;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInput;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Single decision point for the login exemption declaration (AV5): form
 * enum, companion-field rules, account-list normalization and the storage
 * shape. One implementation only — the admin bridge and the re-check tooling
 * consume this class and never re-derive the rules (red line 6).
 *
 * <p>Red-line posture: this is a registration of what the system under test
 * itself guarantees (captcha off / fixed master code / code-exempt accounts).
 * The platform never recognises or solves a captcha; the master code is
 * referenced by vault key, so no secret value ever passes through this shape.
 */
@Component
public class LoginExemptSupport {

    public static final String KIND_NONE = "none";
    public static final String KIND_MASTER_CODE = "master_code";
    public static final String KIND_WHITELIST_ACCOUNTS = "whitelist_accounts";
    public static final String REVIEW_TRIAL_LOGIN = "trial_login";
    public static final String REVIEW_MANUAL = "manual";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PASSED = "passed";
    public static final String STATUS_FAILED = "failed";

    /** Listing cap for code-exempt accounts (worst-scale bound, S-group). */
    public static final int MAX_ACCOUNTS = 50;

    /** Same length ceiling the credential vault pins for cred_key. */
    public static final int MAX_CRED_KEY_LEN = 64;

    // Labels mirror the option words the declaration dialog renders
    // (frontend zh-CN exemptKind), so a refusal listing options always reads
    // with the same words the user sees on screen (single wording source).
    private static final Map<String, String> KIND_LABELS = new TreeMap<>(Map.of(
            KIND_NONE, "需要验证码（不豁免）",
            KIND_MASTER_CODE, "万能验证码",
            KIND_WHITELIST_ACCOUNTS, "白名单账号免验证码"));

    private final ObjectMapper objectMapper;

    public LoginExemptSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Validate and normalize one declaration into its canonical storage JSON.
     * Illegal shapes are refused with wording that lists the legal options.
     */
    public String toStorageJson(LoginExemptInput input) {
        if (input == null || input.kind() == null || input.kind().isBlank()) {
            throw new ServiceException(
                    "登录豁免形态未填写——请在项目详情「环境配置」该行点「登录豁免」，"
                            + "在弹窗「豁免方式」中选择，可选：" + kindOptions() + "。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String kind = input.kind().trim();
        if (!KIND_LABELS.containsKey(kind)) {
            throw new ServiceException(
                    "登录豁免形态「" + kind + "」不在登记范围——请在「登录豁免声明」弹窗"
                            + "把「豁免方式」改为以下之一，可选：" + kindOptions() + "。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String reviewMethod = requireReviewMethod(input.reviewMethod());
        String credKey = null;
        List<String> accounts = List.of();
        switch (kind) {
            case KIND_MASTER_CODE -> credKey = requireCredKey(input.credKey());
            case KIND_WHITELIST_ACCOUNTS -> accounts = requireAccounts(input.accounts());
            default -> {
                // "none" claims nothing else: companion fields are dropped so
                // a stale key/account list can never masquerade as in force.
            }
        }
        try {
            java.util.Map<String, Object> storage = new java.util.LinkedHashMap<>();
            storage.put("kind", kind);
            storage.put("credKey", credKey);
            storage.put("accounts", accounts);
            storage.put("reviewMethod", reviewMethod);
            storage.put("reviewStatus", STATUS_PENDING);
            storage.put("reviewDetail", "");
            storage.put("reviewedAt", "");
            return objectMapper.writeValueAsString(storage);
        } catch (Exception writeFailed) {
            throw new ServiceException("登录豁免声明保存失败（声明内容写入未成功）；请在项目详情「环境配置」该行点「登录豁免」重新保存，持续失败时请反馈本页面异常。",
                    BizErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Read view over the stored slot. NULL (legacy rows, never-declared
     * environments) and unparseable storage both land on the honest
     * undeclared state — reading a declaration must never crash a page.
     */
    public LoginExemptInfo viewOf(long envId, long projectId, String storageJson) {
        if (storageJson == null || storageJson.isBlank()) {
            return undeclared(envId, projectId);
        }
        try {
            JsonNode node = objectMapper.readTree(storageJson);
            if (!node.isObject() || !node.hasNonNull("kind")) {
                return undeclared(envId, projectId);
            }
            List<String> accounts = new ArrayList<>();
            for (JsonNode entry : node.path("accounts")) {
                accounts.add(entry.asText());
            }
            return new LoginExemptInfo(envId, projectId, true,
                    text(node, "kind"), text(node, "credKey"), accounts,
                    text(node, "reviewMethod"), text(node, "reviewStatus"),
                    text(node, "reviewDetail"), text(node, "reviewedAt"));
        } catch (Exception broken) {
            return undeclared(envId, projectId);
        }
    }

    private LoginExemptInfo undeclared(long envId, long projectId) {
        return new LoginExemptInfo(envId, projectId, false,
                null, null, List.of(), null, null, null, null);
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isEmpty() ? null : value;
    }

    private String requireReviewMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ServiceException(
                    "复核方式未填写——请在「登录豁免声明」弹窗的「复核方式」中选择，"
                            + "可选：试登录观察 / 人工复核。", BizErrorCode.VALIDATION_ERROR);
        }
        String method = raw.trim();
        if (!REVIEW_TRIAL_LOGIN.equals(method) && !REVIEW_MANUAL.equals(method)) {
            throw new ServiceException(
                    "复核方式「" + method + "」不被支持——请在「登录豁免声明」弹窗"
                            + "把「复核方式」改为：试登录观察 / 人工复核。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return method;
    }

    private String requireCredKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ServiceException(
                    "豁免形态为「万能验证码」时必须填写凭据键——请先在「凭据保险箱」页建档万能码凭据，"
                            + "再到「登录豁免声明」弹窗的「万能验证码凭据键」引用键名"
                            + "（口令值不填在这里，只保存在凭据库中打码留存）。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String key = raw.trim();
        if (key.length() > MAX_CRED_KEY_LEN
                || !EnvParamCipherSupport.KEY_PATTERN.matcher(key).matches()) {
            throw new ServiceException(
                    "凭据键「" + key + "」格式不合规——需与「凭据保险箱」建档同一规则："
                            + "字母或下划线开头，仅字母、数字、下划线，不超过 64 位；"
                            + "请在「登录豁免声明」弹窗的「万能验证码凭据键」改填已建档的键名。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return key;
    }

    private List<String> requireAccounts(List<String> raw) {
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        if (raw != null) {
            for (String entry : raw) {
                if (entry != null && !entry.isBlank()) {
                    cleaned.add(entry.trim());
                }
            }
        }
        if (cleaned.isEmpty()) {
            throw new ServiceException(
                    "豁免形态为「白名单账号免验证码」时至少要有一个免验证码账号——"
                            + "请在「登录豁免声明」弹窗的「免验证码账号」输入账号后回车添加。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (cleaned.size() > MAX_ACCOUNTS) {
            throw new ServiceException(
                    "免验证码账号清单超过上限 " + MAX_ACCOUNTS + " 个——"
                            + "请在「登录豁免声明」弹窗的「免验证码账号」删除多余项，"
                            + "按被测环境实际白名单收窄登记。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return List.copyOf(cleaned);
    }

    /** True when the form is one the re-check table knows how to review. */
    public static boolean isKnownKind(String kind) {
        return KIND_LABELS.containsKey(kind);
    }

    /** Plain label of a known exemption form (wording single source). */
    public static String kindLabel(String kind) {
        return KIND_LABELS.getOrDefault(kind, kind);
    }

    /**
     * Rebuild the storage slot carrying a fresh re-check verdict while the
     * declaration itself stays untouched: a re-review overwrites the review
     * fields in place and never appends history (one slot, one writer).
     */
    public String withReview(LoginExemptInfo info, String status, String detail, String reviewedAt) {
        try {
            java.util.Map<String, Object> storage = new java.util.LinkedHashMap<>();
            storage.put("kind", info.kind());
            storage.put("credKey", info.credKey());
            storage.put("accounts", info.accounts() == null ? List.of() : info.accounts());
            storage.put("reviewMethod", info.reviewMethod());
            storage.put("reviewStatus", status);
            storage.put("reviewDetail", detail == null ? "" : detail);
            storage.put("reviewedAt", reviewedAt == null ? "" : reviewedAt);
            return objectMapper.writeValueAsString(storage);
        } catch (Exception writeFailed) {
            throw new ServiceException("登录豁免复核结果保存失败（复核结果写入未成功）；请在项目详情「环境配置」该行点「登录豁免」重新提交复核，持续失败时请反馈本页面异常。",
                    BizErrorCode.INTERNAL_ERROR);
        }
    }

    static String kindOptions() {
        return String.join(" / ", KIND_LABELS.values());
    }
}
