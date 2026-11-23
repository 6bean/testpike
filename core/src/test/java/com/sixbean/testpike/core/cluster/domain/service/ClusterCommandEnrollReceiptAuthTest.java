package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.controller.ClusterCommandInternalController;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandInput;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssueInput;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssuedView;
import com.sixbean.testpike.core.cluster.domain.component.EnrollTokenCodec;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.support.ExecutorFixtureRegistration;
import com.sixbean.testpike.core.support.ClusterCommandFixtureLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Enrollment-side identity proof for the dedicated command queue receipts
 * (03 §2.9, the second half of the bidirectional authentication contract).
 * <p>The claim leg already proves the machine (token digest resolution plus
 * bound-fingerprint matching inside pollClaimedForEnroll). Progress and result,
 * however, historically trusted a self-reported claimerId: any intranet process
 * that could read a commandId + claimerId pair could advance or finalize a
 * command on another machine's behalf. This suite pins the fail-closed shape:
 * receipts for enroll-token target commands must carry tokenDigest plus the
 * bound fingerprint, and the server reconciles digest→tokenId==claimerId plus
 * bound_fingerprint before accepting the write.
 * <p>Scope by adjudication: enforcement applies ONLY to target_type
 * 'enroll-token'. Executor-target receipts keep the documented intranet-trust
 * shape with zero drift — neither widened nor relaxed here.
 * <p>Controller face, real dev database, aspect wiring never mocked; bodies
 * mirror exactly what the api bridge forwards after folding the plaintext
 * token into a sha256 digest (core never receives plaintext).
 */
@SpringBootTest(classes = CoreApplication.class)
class ClusterCommandEnrollReceiptAuthTest {

    @Autowired
    private ClusterCommandInternalController controller;
    @Autowired
    private ClusterCommandService commandService;
    @Autowired
    private ClusterEnrollTokenService enrollTokenService;
    @Autowired
    private EnrollTokenCodec tokenCodec;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ClusterCommandFixtureLedger fixtureLedger;
    private final List<Long> trackedExecutors = new CopyOnWriteArrayList<>();

    @BeforeEach
    void markWatermarks() {
        fixtureLedger = new ClusterCommandFixtureLedger(jdbcTemplate, "rcptauth-");
        fixtureLedger.markWatermarks();
    }

    @AfterEach
    void sweepRunRows() {
        fixtureLedger.sweepRunRows(trackedExecutors);
    }

    /** A token issued and redeemed once so the row carries a bound fingerprint. */
    private record BoundToken(long id, String digest, String fingerprint) {
    }

    private BoundToken issueAndBind(String fingerprint) {
        EnrollTokenIssuedView issued = enrollTokenService.issue(new EnrollTokenIssueInput(
                "executor", null, 5, "receipt-auth-suite", "admin"));
        enrollTokenService.enroll(issued.token(), fingerprint);
        return new BoundToken(issued.id(), tokenCodec.digest(issued.token()), fingerprint);
    }

    /** node-self-check is a host-operation type: enroll-token target only. */
    private ClusterCommandView enqueueForToken(long tokenId) {
        return commandService.enqueue(new ClusterCommandInput(
                "node-self-check", "enroll-token", tokenId, Map.of(), "receipt-auth-suite"));
    }

    /** Claim through the controller exactly like the api bridge forwards it. */
    private List<ClusterCommandView> claimByToken(BoundToken token) {
        Map<String, Object> body = new HashMap<>();
        body.put("tokenDigest", token.digest());
        body.put("fingerprint", token.fingerprint());
        body.put("limit", 5);
        return controller.poll(body).getData();
    }

    /** Progress body shape; digest/fingerprint keys exist only when non-null. */
    private Map<String, Object> progressBody(long commandId, long claimerId,
                                             String digest, String fingerprint) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandId", commandId);
        body.put("claimerId", claimerId);
        body.put("step", "ack");
        body.put("message", "enrollment receipt probe");
        if (digest != null) {
            body.put("tokenDigest", digest);
        }
        if (fingerprint != null) {
            body.put("fingerprint", fingerprint);
        }
        return body;
    }

    /** Result body shape; digest/fingerprint keys exist only when non-null. */
    private Map<String, Object> resultBody(long commandId, long claimerId,
                                           String digest, String fingerprint) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandId", commandId);
        body.put("claimerId", claimerId);
        body.put("success", true);
        body.put("resultDetail", "enrollment receipt probe");
        if (digest != null) {
            body.put("tokenDigest", digest);
        }
        if (fingerprint != null) {
            body.put("fingerprint", fingerprint);
        }
        return body;
    }

    /**
     * Contract: a receipt with no identity keys for an enroll-token command is
     * refused on both legs (fail-closed, not a silent pass — the historical hole).
     */
    @Test
    void receiptWithoutIdentity_enrollTarget_progressAndResultRefused() {
        BoundToken token = issueAndBind("fp-rcptauth-A");
        ClusterCommandView cmd = enqueueForToken(token.id());
        assertEquals(1, claimByToken(token).size(), "令牌形态领取应恰好取到本条命令");

        assertThrows(ServiceException.class, () -> controller.progress(
                progressBody(cmd.id(), token.id(), null, null)),
                "enroll-token 命令的进度回执缺身份必须 fail-closed 拒绝");
        assertThrows(ServiceException.class, () -> controller.result(
                resultBody(cmd.id(), token.id(), null, null)),
                "enroll-token 命令的终态回执缺身份必须 fail-closed 拒绝");
    }

    /**
     * Contract: a receipt whose fingerprint is not the one bound on the token
     * row (another machine replaying the digest) is refused.
     */
    @Test
    void receiptWithForeignFingerprint_enrollTarget_refused() {
        BoundToken token = issueAndBind("fp-rcptauth-A");
        ClusterCommandView cmd = enqueueForToken(token.id());
        claimByToken(token);

        assertThrows(ServiceException.class, () -> controller.progress(
                progressBody(cmd.id(), token.id(), token.digest(), "fp-rcptauth-B")),
                "指纹与令牌绑定不一致的进度回执必须拒绝");
        assertThrows(ServiceException.class, () -> controller.result(
                resultBody(cmd.id(), token.id(), token.digest(), "fp-rcptauth-B")),
                "指纹与令牌绑定不一致的终态回执必须拒绝");
    }

    /**
     * Contract: a receipt carrying another (valid, bound) token's digest is
     * refused — digest must resolve to exactly the claimed_by token row.
     */
    @Test
    void receiptWithForeignTokenDigest_enrollTarget_refused() {
        BoundToken claimer = issueAndBind("fp-rcptauth-A");
        BoundToken stranger = issueAndBind("fp-rcptauth-S");
        ClusterCommandView cmd = enqueueForToken(claimer.id());
        claimByToken(claimer);

        assertThrows(ServiceException.class, () -> controller.progress(
                progressBody(cmd.id(), claimer.id(), stranger.digest(), claimer.fingerprint())),
                "他令牌摘要不得替本令牌回执行——digest 解析出的令牌行必须等于 claimed_by");
    }

    /**
     * Anti-regression pin: the honestly bound machine passes both legs with its
     * own digest and fingerprint once enforcement lands.
     */
    @Test
    void receiptWithCorrectIdentity_enrollTarget_accepted() {
        BoundToken token = issueAndBind("fp-rcptauth-A");
        ClusterCommandView cmd = enqueueForToken(token.id());
        claimByToken(token);

        controller.progress(progressBody(cmd.id(), token.id(), token.digest(), token.fingerprint()));
        controller.result(resultBody(cmd.id(), token.id(), token.digest(), token.fingerprint()));
    }

    /**
     * Zero-drift pin: executor-target receipts keep the documented self-reported
     * claimerId shape — no identity keys required, behavior unchanged.
     */
    @Test
    void executorTarget_legacyReceiptShape_stillAccepted() {
        long exId = ExecutorFixtureRegistration.register(
                executorService, "rcptauth-exec", trackedExecutors);
        ClusterCommandView cmd = commandService.enqueue(new ClusterCommandInput(
                "engine-upgrade", "executor", exId,
                Map.of("engines", List.of("MOBILEWRIGHT")), "receipt-auth-suite"));
        assertEquals(1, controller.poll(Map.of("executorId", exId, "limit", 5))
                .getData().size(), "executor 直领形态零漂移");

        controller.progress(progressBody(cmd.id(), exId, null, null));
        controller.result(resultBody(cmd.id(), exId, null, null));
    }
}
