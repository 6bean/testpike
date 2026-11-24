package com.sixbean.testpike.core.session.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInput;
import com.sixbean.testpike.core.session.api.dto.LoginExemptReviewSignal;
import com.sixbean.testpike.core.session.api.dto.LoginExemptReviewVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R63 refusal-guidance contract for the login-exemption declaration door.
 *
 * <p>Every user-reachable refusal raised by {@link LoginExemptSupport} must
 * name the offending input value and carry a repair entry the human can
 * actually follow on screen. The entry literals are pinned against the
 * shipped UI wording (frontend zh-CN tab, row action, dialog title, the four
 * dialog fields and the vault menu name), so the server never points at a
 * page that does not exist. The static gate
 * (tools/error_guidance_coverage_gate.py) scans the sentence literals; this
 * class pins the same contract through the real component so a future
 * rewrite cannot silently drop a pointer again.
 *
 * <p>No infrastructure collaborators are involved — the component is built
 * directly, so every code path under test is the real product code.
 */
class LoginExemptGuidanceTest {

    private final LoginExemptSupport support = new LoginExemptSupport(new ObjectMapper());
    private final LoginExemptReviewSupport reviewSupport = new LoginExemptReviewSupport();

    private ServiceException refusalOf(LoginExemptInput input) {
        return assertThrows(ServiceException.class, () -> support.toStorageJson(input));
    }

    @Test
    void blankFormRefusalPointsToTheEnvironmentConfigEntry() {
        ServiceException e = refusalOf(new LoginExemptInput(" ", null, null, "manual"));
        assertTrue(e.getMessage().contains("「环境配置」"),
                "refusal names the literal tab where the row action lives: " + e.getMessage());
        assertTrue(e.getMessage().contains("「登录豁免」"),
                "refusal names the literal row action that opens the dialog: " + e.getMessage());
        assertTrue(e.getMessage().contains("「豁免方式」"),
                "refusal names the literal field to fix: " + e.getMessage());
        assertTrue(e.getMessage().contains("可选"),
                "refusal lists the legal options: " + e.getMessage());
    }

    @Test
    void unknownFormRefusalQuotesTheValueAndTheDialogEntry() {
        ServiceException e = refusalOf(new LoginExemptInput("magic_mode", null, null, "manual"));
        assertTrue(e.getMessage().contains("「magic_mode」"),
                "refusal echoes the offending value: " + e.getMessage());
        assertTrue(e.getMessage().contains("「登录豁免声明」"),
                "refusal names the literal dialog title: " + e.getMessage());
        assertTrue(e.getMessage().contains("「豁免方式」"),
                "refusal names the literal field to change: " + e.getMessage());
    }

    @Test
    void formOptionListingMatchesTheOnScreenDialogLabels() {
        ServiceException e = refusalOf(new LoginExemptInput("magic_mode", null, null, "manual"));
        assertTrue(e.getMessage().contains("需要验证码（不豁免）"),
                "listing uses the same words the dialog dropdown shows: " + e.getMessage());
        assertTrue(e.getMessage().contains("万能验证码"),
                "listing uses the same words the dialog dropdown shows: " + e.getMessage());
        assertTrue(e.getMessage().contains("白名单账号免验证码"),
                "listing uses the same words the dialog dropdown shows: " + e.getMessage());
    }

    @Test
    void blankReviewMethodRefusalNamesTheFieldAndBothScreenOptions() {
        ServiceException e = refusalOf(new LoginExemptInput("none", null, null, " "));
        assertTrue(e.getMessage().contains("「复核方式」"),
                "refusal names the literal field: " + e.getMessage());
        assertTrue(e.getMessage().contains("试登录观察"),
                "refusal quotes the option word shown on screen: " + e.getMessage());
        assertTrue(e.getMessage().contains("人工复核"),
                "refusal quotes the option word shown on screen: " + e.getMessage());
    }

    @Test
    void unsupportedReviewMethodRefusalQuotesValueAndEntry() {
        ServiceException e = refusalOf(new LoginExemptInput("none", null, null, "auto_pilot"));
        assertTrue(e.getMessage().contains("「auto_pilot」"),
                "refusal echoes the offending value: " + e.getMessage());
        assertTrue(e.getMessage().contains("「登录豁免声明」"),
                "refusal names the literal dialog entry: " + e.getMessage());
        assertTrue(e.getMessage().contains("「复核方式」"),
                "refusal names the literal field to change: " + e.getMessage());
    }

    @Test
    void missingCredKeyRefusalPointsToTheVaultPage() {
        ServiceException e = refusalOf(new LoginExemptInput("master_code", " ", null, "manual"));
        assertTrue(e.getMessage().contains("「凭据保险箱」"),
                "refusal quotes the literal vault menu name: " + e.getMessage());
        assertFalse(e.getMessage().contains("凭据管理"),
                "no refusal may point at a page name the menu does not show: " + e.getMessage());
    }

    @Test
    void malformedCredKeyRefusalQuotesKeyAndNamesBothEntries() {
        ServiceException e = refusalOf(new LoginExemptInput("master_code", "1 bad key!", null, "manual"));
        assertTrue(e.getMessage().contains("「1 bad key!」"),
                "refusal echoes the offending key name: " + e.getMessage());
        assertTrue(e.getMessage().contains("「凭据保险箱」"),
                "refusal names the vault page that pins the same rule: " + e.getMessage());
        assertTrue(e.getMessage().contains("「万能验证码凭据键」"),
                "refusal names the literal field to refill: " + e.getMessage());
    }

    @Test
    void emptyWhitelistRefusalPointsToAddAccountInteractionInDialog() {
        ServiceException e = refusalOf(new LoginExemptInput("whitelist_accounts", null, List.of(), "trial_login"));
        assertTrue(e.getMessage().contains("「登录豁免声明」"),
                "refusal names the literal dialog entry: " + e.getMessage());
        assertTrue(e.getMessage().contains("「免验证码账号」"),
                "refusal names the literal field: " + e.getMessage());
        assertTrue(e.getMessage().contains("回车"),
                "refusal quotes the literal add interaction the input placeholder shows: " + e.getMessage());
    }

    @Test
    void overCapWhitelistRefusalPointsToTrimmingInDialog() {
        List<String> accounts = IntStream.rangeClosed(1, LoginExemptSupport.MAX_ACCOUNTS + 1)
                .mapToObj(i -> "exempt-user-" + i)
                .toList();
        ServiceException e = refusalOf(new LoginExemptInput("whitelist_accounts", null, accounts, "manual"));
        assertTrue(e.getMessage().contains("上限"),
                "refusal states the numeric cap consequence: " + e.getMessage());
        assertTrue(e.getMessage().contains("「免验证码账号」"),
                "refusal names the literal field to trim: " + e.getMessage());
        assertTrue(e.getMessage().contains("「登录豁免声明」"),
                "refusal names the literal dialog entry: " + e.getMessage());
    }

    @Test
    void reviewRefusalForAFormOutsideTheTablePointsAtTheDialogField() {
        ServiceException e = assertThrows(ServiceException.class, () -> reviewSupport.decide(
                "sms_code", new LoginExemptReviewSignal(
                        LoginExemptReviewSupport.SIGNAL_TRIAL_LOGIN_OK, null)));
        assertTrue(e.getMessage().contains("「sms_code」"),
                "refusal echoes the stored form value: " + e.getMessage());
        assertTrue(e.getMessage().contains("「环境配置」"),
                "refusal names the literal tab where the row lives: " + e.getMessage());
        assertTrue(e.getMessage().contains("「登录豁免」"),
                "refusal names the literal row action that reopens the dialog: " + e.getMessage());
        assertTrue(e.getMessage().contains("「豁免方式」"),
                "refusal names the literal field that holds the form: " + e.getMessage());
        assertTrue(e.getMessage().contains("万能验证码"),
                "refusal lists the forms the dialog offers: " + e.getMessage());
    }

    @Test
    void reviewRefusalForAnUnknownSignalQuotesValueAndNamesTheConclusionField() {
        ServiceException e = assertThrows(ServiceException.class, () -> reviewSupport.decide(
                LoginExemptSupport.KIND_NONE,
                new LoginExemptReviewSignal("captcha_solved", null)));
        assertTrue(e.getMessage().contains("「captcha_solved」"),
                "refusal echoes the offending signal value: " + e.getMessage());
        assertTrue(e.getMessage().contains("「复核结论」"),
                "refusal names the literal field the reviewer picks in: " + e.getMessage());
        assertTrue(e.getMessage().contains("万能验证码被拒绝"),
                "listing uses the option words the dialog dropdown shows: " + e.getMessage());
        assertTrue(e.getMessage().contains("人工确认不成立"),
                "listing uses the option words the dialog dropdown shows: " + e.getMessage());
        assertTrue(e.getMessage().contains("trial_login_ok"),
                "the machine value stays quotable for tooling callers: " + e.getMessage());
    }

    @Test
    void masterCodeRejectionWordingPointsAtTheVaultPageNotAnImaginaryPage() {
        LoginExemptReviewVerdict verdict = reviewSupport.decide(
                LoginExemptSupport.KIND_MASTER_CODE,
                new LoginExemptReviewSignal(LoginExemptReviewSupport.SIGNAL_MASTER_CODE_REJECTED, null));
        assertTrue(verdict.detail().contains("「凭据保险箱」"),
                "the repair door must be the literal menu name: " + verdict.detail());
        assertTrue(verdict.detail().contains("「万能验证码凭据键」"),
                "the repair door names the field that carries the key: " + verdict.detail());
        assertFalse(verdict.detail().contains("凭据管理"),
                "no wording may point at a page the menu does not show: " + verdict.detail());
    }
}
