package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.session.api.dto.LoginExemptReviewSignal;
import com.sixbean.testpike.core.session.api.dto.LoginExemptReviewVerdict;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single decision point of the login exemption re-check (AV5): turns one
 * observation into a review status plus the wording that explains it. The
 * declaration page, the ledger light and the pre-flight gate all consume this
 * table and never re-derive the rules (red line 6).
 *
 * <p>Posture on the captcha surface (red line 1): the platform never
 * recognises, solves or guesses a verification code. A re-check only reports
 * what a trial login observed. Signals that prove nothing for the declared
 * form land on pending, so an unverified claim can never masquerade as
 * verified; the failed wording always states what to fix and where.
 */
@Component
public class LoginExemptReviewSupport {

    public static final String SIGNAL_TRIAL_LOGIN_OK = "trial_login_ok";
    public static final String SIGNAL_CAPTCHA_STILL_REQUIRED = "captcha_still_required";
    public static final String SIGNAL_MASTER_CODE_REJECTED = "master_code_rejected";
    public static final String SIGNAL_UNDETERMINED = "undetermined";
    public static final String SIGNAL_MANUAL_CONFIRMED = "manual_confirmed";
    public static final String SIGNAL_MANUAL_REJECTED = "manual_rejected";

    /** Reviewer note length cap: the slot stays bounded (S-group worst scale). */
    public static final int MAX_NOTE_LEN = 300;

    // Signal vocabulary paired with the option words the declaration dialog
    // shows in its review-conclusion dropdown (frontend zh-CN exemptSignal),
    // so a refusal listing options always reads with the words on screen while
    // the machine value stays quotable for tooling callers (single wording
    // source, same idiom as the exemption-form labels above). Insertion order
    // is the on-screen option order, hence explicit sequential puts.
    private static final Map<String, String> SIGNAL_LABELS = new LinkedHashMap<>();

    static {
        SIGNAL_LABELS.put(SIGNAL_TRIAL_LOGIN_OK, "试登录成功且未出现验证码");
        SIGNAL_LABELS.put(SIGNAL_CAPTCHA_STILL_REQUIRED, "试登录仍要求验证码");
        SIGNAL_LABELS.put(SIGNAL_MASTER_CODE_REJECTED, "万能验证码被拒绝");
        SIGNAL_LABELS.put(SIGNAL_UNDETERMINED, "无法判定");
        SIGNAL_LABELS.put(SIGNAL_MANUAL_CONFIRMED, "人工确认成立");
        SIGNAL_LABELS.put(SIGNAL_MANUAL_REJECTED, "人工确认不成立");
    }

    private static final List<String> SIGNALS = List.copyOf(SIGNAL_LABELS.keySet());

    /**
     * Judge one observation against the declared exemption form.
     *
     * @param kind     exemption form recorded on the environment profile
     * @param signal   reported observation (never a solved captcha)
     * @return status plus the wording stored on the declaration slot
     */
    public LoginExemptReviewVerdict decide(String kind, LoginExemptReviewSignal signal) {
        String form = requireKind(kind);
        String observed = requireSignal(signal);
        String note = trimNote(signal.note());
        return switch (observed) {
            case SIGNAL_MANUAL_CONFIRMED -> new LoginExemptReviewVerdict(LoginExemptSupport.STATUS_PASSED,
                    manualWording("人工复核通过", note));
            case SIGNAL_MANUAL_REJECTED -> new LoginExemptReviewVerdict(LoginExemptSupport.STATUS_FAILED,
                    manualWording("人工复核未通过", note));
            case SIGNAL_TRIAL_LOGIN_OK -> new LoginExemptReviewVerdict(LoginExemptSupport.STATUS_PASSED,
                    "试登录复核通过：按登记的「" + LoginExemptSupport.kindLabel(form)
                            + "」完成登录，未出现无法自动处理的验证码拦截。");
            case SIGNAL_CAPTCHA_STILL_REQUIRED -> captchaStillRequired(form);
            case SIGNAL_MASTER_CODE_REJECTED -> masterCodeRejected(form);
            default -> new LoginExemptReviewVerdict(LoginExemptSupport.STATUS_PENDING,
                    "本次试登录未取得可判定结果（目标不可达或未跑完），豁免声明保持待复核。");
        };
    }

    private LoginExemptReviewVerdict captchaStillRequired(String form) {
        if (LoginExemptSupport.KIND_MASTER_CODE.equals(form)) {
            // a master-code environment shows a code field by design; seeing
            // the field alone says nothing about whether the fixed code works.
            return new LoginExemptReviewVerdict(LoginExemptSupport.STATUS_PENDING,
                    "复核信号不足以判定：万能码形态下页面本就显示验证码输入位，"
                            + "需以试登录结果（通过或被拒）复核，豁免声明保持待复核。");
        }
        return new LoginExemptReviewVerdict(LoginExemptSupport.STATUS_FAILED,
                LoginExemptSupport.KIND_WHITELIST_ACCOUNTS.equals(form)
                        ? "「白名单账号免码」声明未通过复核：免码账号在试登录时仍被要求填写验证码。"
                        + "请核对被测环境的免码白名单是否包含该账号；平台不会识别或绕过验证码。"
                        : "「无验证码」声明未通过复核：试登录时页面仍要求填写验证码。"
                        + "请由被测环境按承诺关闭验证码后再复核；平台不会识别或绕过验证码。");
    }

    private LoginExemptReviewVerdict masterCodeRejected(String form) {
        if (!LoginExemptSupport.KIND_MASTER_CODE.equals(form)) {
            return new LoginExemptReviewVerdict(LoginExemptSupport.STATUS_PENDING,
                    "该信号与登记的「" + LoginExemptSupport.kindLabel(form) + "」形态不对应，"
                            + "不足以下结论，豁免声明保持待复核。");
        }
        return new LoginExemptReviewVerdict(LoginExemptSupport.STATUS_FAILED,
                "万能码试登录被拒——豁免声明未通过复核。请到「凭据保险箱」页核对该键的万能码与被测环境约定是否一致，"
                        + "确认后在「登录豁免声明」弹窗的「万能验证码凭据键」重新引用并提交复核；平台不会猜测、穷举或识别验证码。");
    }

    private String manualWording(String heading, String note) {
        return "（人工复核）" + heading + (note.isBlank() ? "" : "：" + note);
    }

    private String trimNote(String note) {
        String cleaned = note == null ? "" : note.trim();
        return cleaned.length() <= MAX_NOTE_LEN ? cleaned : cleaned.substring(0, MAX_NOTE_LEN);
    }

    private String requireKind(String kind) {
        String form = kind == null ? "" : kind.trim();
        if (!LoginExemptSupport.isKnownKind(form)) {
            throw new ServiceException("登录豁免形态「" + form + "」不在复核范围——请在项目详情"
                    + "「环境配置」该行点「登录豁免」，把弹窗「豁免方式」改为以下之一："
                    + LoginExemptSupport.kindOptions() + "。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return form;
    }

    private String requireSignal(LoginExemptReviewSignal signal) {
        String observed = signal == null || signal.signal() == null
                ? "" : signal.signal().trim();
        if (!SIGNALS.contains(observed)) {
            throw new ServiceException("复核信号「" + observed + "」不被支持——请在项目详情"
                    + "「环境配置」该行点「登录豁免」，在弹窗「复核结论」改选以下之一："
                    + signalOptions() + "。", BizErrorCode.VALIDATION_ERROR);
        }
        return observed;
    }

    /** Option listing in dialog order: on-screen words with the machine value quoted. */
    private static String signalOptions() {
        StringBuilder joined = new StringBuilder();
        SIGNAL_LABELS.forEach((code, label) -> {
            if (!joined.isEmpty()) {
                joined.append(" / ");
            }
            joined.append(label).append('(').append(code).append(')');
        });
        return joined.toString();
    }
}
