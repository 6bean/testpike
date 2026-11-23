package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.session.api.dto.LoginExemptReviewSignal;
import com.sixbean.testpike.core.session.api.dto.LoginExemptReviewVerdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trial-login re-check decision table for the login exemption declaration
 * (AV5 re-check face). The whole point of the exemption channel is honesty:
 * a claim the system under test made must be confirmed by observation, and a
 * claim contradicted by observation must be marked failed with wording that
 * says so — never silently kept as "passed".
 *
 * <p>Red line 1 is asserted structurally here: the signal vocabulary has no
 * member meaning "the platform solved/recognised a captcha", the honest
 * wording of a failed master_code review points at the environment promise
 * (and at the credential vault for the code), and an inconclusive trial
 * stays pending instead of becoming a false guarantee.
 */
class LoginExemptReviewSupportTest {

    private final LoginExemptReviewSupport support = new LoginExemptReviewSupport();

    private LoginExemptReviewSignal sig(String signal) {
        return new LoginExemptReviewSignal(signal, null);
    }

    @Test
    void captchaFreeEnvironmentClaimConfirmedByTrialLoginPasses() {
        LoginExemptReviewVerdict verdict =
                support.decide(LoginExemptSupport.KIND_NONE, sig(LoginExemptReviewSupport.SIGNAL_TRIAL_LOGIN_OK));
        assertEquals(LoginExemptSupport.STATUS_PASSED, verdict.status());
        assertTrue(verdict.detail().contains("验证码"), "passed wording must state what was observed");
    }

    @Test
    void captchaStillRequiredContradictsTheNoneClaimAndFails() {
        LoginExemptReviewVerdict verdict = support.decide(LoginExemptSupport.KIND_NONE,
                sig(LoginExemptReviewSupport.SIGNAL_CAPTCHA_STILL_REQUIRED));
        assertEquals(LoginExemptSupport.STATUS_FAILED, verdict.status(),
                "a claim the environment contradicts must land on 未通过复核, not on pending");
        assertTrue(verdict.detail().contains("未通过复核"), verdict.detail());
        assertTrue(verdict.detail().contains("不会识别"),
                "red line 1: the refusal wording must declare that the platform never recognises a captcha");
    }

    @Test
    void acceptedMasterCodePassesTheMasterCodeClaim() {
        LoginExemptReviewVerdict verdict = support.decide(LoginExemptSupport.KIND_MASTER_CODE,
                sig(LoginExemptReviewSupport.SIGNAL_TRIAL_LOGIN_OK));
        assertEquals(LoginExemptSupport.STATUS_PASSED, verdict.status());
    }

    @Test
    void rejectedMasterCodeFailsAndPointsAtTheVaultKey() {
        LoginExemptReviewVerdict verdict = support.decide(LoginExemptSupport.KIND_MASTER_CODE,
                sig(LoginExemptReviewSupport.SIGNAL_MASTER_CODE_REJECTED));
        assertEquals(LoginExemptSupport.STATUS_FAILED, verdict.status());
        assertTrue(verdict.detail().contains("凭据管理"), verdict.detail());
        assertTrue(verdict.detail().contains("万能码"), verdict.detail());
    }

    @Test
    void captchaFieldUnderMasterCodeIsNotConclusiveSoStaysPending() {
        LoginExemptReviewVerdict verdict = support.decide(LoginExemptSupport.KIND_MASTER_CODE,
                sig(LoginExemptReviewSupport.SIGNAL_CAPTCHA_STILL_REQUIRED));
        assertEquals(LoginExemptSupport.STATUS_PENDING, verdict.status(),
                "a master-code environment shows a code field by design; that signal proves nothing");
        assertTrue(verdict.detail().contains("待复核"), verdict.detail());
    }

    @Test
    void whitelistedAccountStillAskedForCodeFailsTheWhitelistClaim() {
        LoginExemptReviewVerdict verdict = support.decide(LoginExemptSupport.KIND_WHITELIST_ACCOUNTS,
                sig(LoginExemptReviewSupport.SIGNAL_CAPTCHA_STILL_REQUIRED));
        assertEquals(LoginExemptSupport.STATUS_FAILED, verdict.status());
        assertTrue(verdict.detail().contains("白名单"), verdict.detail());
    }

    @Test
    void inconclusiveTrialKeepsPendingAndNeverInventsAPass() {
        for (String kind : new String[] {LoginExemptSupport.KIND_NONE,
                LoginExemptSupport.KIND_MASTER_CODE, LoginExemptSupport.KIND_WHITELIST_ACCOUNTS}) {
            LoginExemptReviewVerdict verdict = support.decide(kind,
                    sig(LoginExemptReviewSupport.SIGNAL_UNDETERMINED));
            assertEquals(LoginExemptSupport.STATUS_PENDING, verdict.status(), kind);
        }
    }

    @Test
    void manualReviewRecordsTheHumanVerdictWithItsNote() {
        LoginExemptReviewSignal rejected = new LoginExemptReviewSignal(
                LoginExemptReviewSupport.SIGNAL_MANUAL_REJECTED, "环境组确认万能码已下线");
        LoginExemptReviewVerdict verdict = support.decide(LoginExemptSupport.KIND_MASTER_CODE, rejected);
        assertEquals(LoginExemptSupport.STATUS_FAILED, verdict.status());
        assertTrue(verdict.detail().contains("环境组确认万能码已下线"), "the reviewer note must survive");
        assertTrue(verdict.detail().contains("人工复核"), verdict.detail());
    }

    @Test
    void manualNoteIsTruncatedButNeverDroppedWhole() {
        String longNote = "理".repeat(400);
        LoginExemptReviewSignal signal = new LoginExemptReviewSignal(
                LoginExemptReviewSupport.SIGNAL_MANUAL_CONFIRMED, longNote);
        LoginExemptReviewVerdict verdict = support.decide(LoginExemptSupport.KIND_NONE, signal);
        assertEquals(LoginExemptSupport.STATUS_PASSED, verdict.status());
        assertTrue(verdict.detail().length() <= LoginExemptReviewSupport.MAX_NOTE_LEN + 64,
                "stored wording must stay bounded (S-group worst scale)");
        assertTrue(verdict.detail().contains("理".repeat(50)), "the note head must stay readable");
    }

    @Test
    void aSignalOutsideTheVocabularyIsRefusedWithTheLegalOptionsListed() {
        ServiceException refused = assertThrows(ServiceException.class,
                () -> support.decide(LoginExemptSupport.KIND_NONE, sig("captcha_solved")));
        assertTrue(refused.getMessage().contains("trial_login_ok"), refused.getMessage());
        assertTrue(refused.getMessage().contains("不被支持"), refused.getMessage());
    }

    @Test
    void anUnknownExemptionFormCannotBeReviewed() {
        ServiceException refused = assertThrows(ServiceException.class,
                () -> support.decide("sms_code", sig(LoginExemptReviewSupport.SIGNAL_TRIAL_LOGIN_OK)));
        assertTrue(refused.getMessage().contains("登录豁免形态"), refused.getMessage());
    }
}
