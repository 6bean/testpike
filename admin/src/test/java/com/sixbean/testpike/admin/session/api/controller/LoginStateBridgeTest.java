package com.sixbean.testpike.admin.session.api.controller;

import com.sixbean.testpike.admin.session.api.dto.LoginStateRegisterRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginStateView;
import com.sixbean.testpike.admin.session.infrastructure.remote.client.CoreLoginStateClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.sixbean.testpike.admin.project.api.dto.ProbeOutcomeView;

/**
 * Login-state admin bridge contract .
 *
 * <p>Asserted surface:
 * 1. Parameter pass-through — filtering, resolution and validation live in the
 * core service; the bridge must not re-implement any of them (red line 6).
 * 2. Honest core wording (404 / fail-closed candidate listing) propagates
 * verbatim, never swallowed or rewritten.
 * 3. Never-echo structural gate — LoginStateView carries zero secret-material
 * components, and the bridge client exposes no reveal or verification-forge
 * method, so plaintext cannot cross this boundary by construction.
 * 4. Permission doors — each user-facing endpoint carries exactly its
 * registered perm:session key (capture/read/manage/consume land here; the
 * verify and exempt doors arrive with their own slices /).
 *
 * <p>The CoreLoginStateClient stand-in is bridge-contract L2 isolation (same
 * family precedent); the real admin-core chain belongs to the batch L3 window.
 */
class LoginStateBridgeTest {

    private final CoreLoginStateClient client = mock(CoreLoginStateClient.class);
    private final com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard guard =
            mock(com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard.class);
    private final LoginStateController controller = new LoginStateController(client, guard);

    private LoginStateView row(long id) {
        return new LoginStateView(id, 7L, "审批人甲", "approver", "审批人甲",
                "token", 41L, "session_approver_a", null,
                List.of("https://app.example.com"), 3,
                LocalDateTime.now().plusHours(1), LocalDateTime.now(), 1,
                "本机直连采集", LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void registerPassesProjectAndPayloadThroughAndReturnsLedgerRowId() {
        LoginStateRegisterRequest request = new LoginStateRegisterRequest(
                7L, "审批人甲", "approver", "审批人甲", "token", "session_approver_a",
                "plain-captured-value", null, List.of("https://app.example.com"), "本机直连采集");
        when(client.register(eq(7L), eq(request))).thenReturn(88L);
        assertEquals(88L, controller.register(request).getData());
        verify(client).register(7L, request);
    }

    @Test
    void updateTakesOwnershipFromTheCoreEchoNotFromTheBody() {
        LoginStateRegisterRequest forged = new LoginStateRegisterRequest(
                99L, "审批人甲", "approver", "审批人甲", "token", "session_approver_a",
                null, null, List.of("https://app.example.com"), "手改备注");
        when(client.findState(5L)).thenReturn(row(5L));   // row lives in project 7
        controller.update(5L, forged);
        verify(guard).assertCanManage(org.mockito.ArgumentMatchers.any(), eq(7L));
        verify(client).update(5L, forged);
    }

    @Test
    void updatePropagatesTheHonestCoreWordingForMissingRows() {
        when(client.findState(999L)).thenThrow(new ServiceException(
                "登录态 #999 不存在——请刷新账本页确认对象。", BizErrorCode.NOT_FOUND));
        ServiceException missing = assertThrows(ServiceException.class, () -> controller.update(
                999L, new LoginStateRegisterRequest(null, "x", null, null, "token", "k",
                        null, null, List.of(), null)));
        assertTrue(missing.getMessage().contains("不存在"), "core wording survives the bridge");
    }

    @Test
    void listKeepsEnabledOnlyFilterOnTheCoreSideWithoutAdminReprocessing() {
        when(client.listStates(7L, null)).thenReturn(List.of(row(1L), row(2L)));
        assertEquals(2, controller.list(7L, null).getData().size());
        verify(client).listStates(eq(7L), eq(null));
        when(client.listStates(7L, Boolean.TRUE)).thenReturn(List.of(row(1L)));
        assertEquals(1, controller.list(7L, Boolean.TRUE).getData().size(),
                "usable-rows filtering belongs to core; admin must not filter again");
    }

    @Test
    void detailPropagatesTheHonestCoreWordingForMissingRows() {
        when(client.findState(1L)).thenReturn(row(1L));
        assertEquals("审批人甲", controller.get(1L).getData().stateName());
        when(client.findState(999L)).thenThrow(new ServiceException(
                "登录态 #999 不存在——请刷新账本页确认对象。", BizErrorCode.NOT_FOUND));
        ServiceException missing = assertThrows(ServiceException.class,
                () -> controller.get(999L));
        assertTrue(missing.getMessage().contains("不存在"),
                "core wording must not be swallowed or rewritten");
    }

    @Test
    void resolvePropagatesFailClosedCandidateListingVerbatim() {
        when(client.resolveState(7L, null, "approver")).thenThrow(new ServiceException(
                "角色「approver」下存在多个登录态（审批人甲、审批人乙），"
                        + "请在用例中指明使用哪一个，系统不代为挑选。",
                BizErrorCode.VALIDATION_ERROR));
        ServiceException ambiguous = assertThrows(ServiceException.class,
                () -> controller.resolve(7L, null, "approver"));
        assertTrue(ambiguous.getMessage().contains("系统不代为挑选"),
                "D9 candidate enumeration must reach the caller intact");
        when(client.resolveState(7L, "审批人甲", null)).thenReturn(row(3L));
        assertEquals(3L, controller.resolve(7L, "审批人甲", null).getData().id());
    }

    @Test
    void lifecycleEndpointsDelegateEnableAndDeleteWithoutLocalChecks() {
        when(client.findState(5L)).thenReturn(row(5L));
        controller.setEnabled(5L, false);
        verify(client).setEnabled(5L, false);
        controller.delete(5L);
        verify(client).delete(5L);
        when(client.findState(6L)).thenThrow(new ServiceException(
                "登录态 #6 不存在，无法调整启用状态。", BizErrorCode.NOT_FOUND));
        assertThrows(ServiceException.class, () -> controller.setEnabled(6L, true));
    }

    @Test
    void viewStructurallyCarriesNoSecretMaterialAndClientExposesNoRevealOrForgeDoor() {
        List<String> components = Arrays.stream(LoginStateView.class.getRecordComponents())
                .map(c -> c.getName()).toList();
        List<String> leaky = components.stream()
                .filter(n -> {
                    String s = n.toLowerCase();
                    return s.contains("secret") || s.contains("cipher")
                            || s.contains("plain") || s.contains("tokenvalue");
                })
                .toList();
        assertTrue(leaky.isEmpty(), "ledger view must never carry secret material: " + leaky);
        List<String> clientMethods = Arrays.stream(CoreLoginStateClient.class.getDeclaredMethods())
                .map(m -> m.getName()).toList();
        // Audited trail read doors are the ONLY reveal-shape
        // names allowed on this bridge, and they must be present — a ledger
        // that silently empties is the no-op guard failure mode R43 forbids.
        List<String> trailDoorLedger = List.of("revealProbeTrailField", "probeTrailFullText");
        assertTrue(clientMethods.containsAll(trailDoorLedger),
                "gated trail read doors must stay on the named ledger: " + clientMethods);
        assertTrue(clientMethods.stream().noneMatch(n -> {
            String s = n.toLowerCase();
            return !trailDoorLedger.contains(n) && (s.contains("reveal") || s.contains("verified"));
        }), "bridge must not expose un-ledgered reveal or verification-writeback doors: "
                + clientMethods);
    }

    @Test
    void eachEndpointCarriesItsRegisteredPermissionKey() throws Exception {
        assertEquals("perm:session:read",
                permOf("list", long.class, Boolean.class));
        assertEquals("perm:session:read", permOf("get", long.class));
        assertEquals("perm:session:capture",
                permOf("register", LoginStateRegisterRequest.class));
        assertEquals("perm:session:manage",
                permOf("update", long.class, LoginStateRegisterRequest.class));
        assertEquals("perm:session:consume",
                permOf("resolve", long.class, String.class, String.class));
        assertEquals("perm:session:manage",
                permOf("setEnabled", long.class, boolean.class));
        assertEquals("perm:session:manage", permOf("delete", long.class));
    }

    private String permOf(String methodName, Class<?>... params) throws Exception {
        Method m = LoginStateController.class.getMethod(methodName, params);
        RequirePermission ann = m.getAnnotation(RequirePermission.class);
        return ann == null ? null : ann.value();
    }

    @Test
    void probeDoorPassesTokenThroughAndKeepsProjectGuardOnTheRow() {
        LoginStateView target = row(77L);
        when(client.findState(77L)).thenReturn(target);
        when(client.probeState(77L, null, null)).thenReturn(
                new ProbeOutcomeView("tok-1", "VALID", "目标接受（2xx）"));
        var out = controller.probe(77L, null, null).getData();
        assertEquals("tok-1", out.runToken());
        assertEquals("VALID", out.verdict());
        verify(client).probeState(77L, null, null);

        // run payload (method + body template) is pure pass-through — the
        // bridge neither validates the verb nor resolves the placeholders.
        com.sixbean.testpike.admin.session.api.dto.LoginStateProbeRunRequest run =
                new com.sixbean.testpike.admin.session.api.dto.LoginStateProbeRunRequest(
                        "POST", "{\"apiKey\": \"{{CRED:cmp_key}}\"}");
        when(client.probeState(77L, "https://t.example/api", run)).thenReturn(
                new ProbeOutcomeView("tok-2", "UNKNOWN", "结果需人工确认"));
        var out2 = controller.probe(77L, "https://t.example/api", run).getData();
        assertEquals("tok-2", out2.runToken());
        verify(client).probeState(77L, "https://t.example/api", run);
    }

    @Test
    void probeEventsWindowPassesCursorThroughWithoutLocalReprocessing() throws Exception {
        when(client.findState(77L)).thenReturn(row(77L));
        when(client.probeEvents(77L, "tok-1", 12L)).thenReturn(List.of(
                new com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView(
                        13L, "step", "收到响应：HTTP 200", null, "2026-11-16T10:00:00")));
        var rows = controller.probeEvents(77L, "tok-1", 12L).getData();
        assertEquals(1, rows.size());
        assertEquals(13L, rows.get(0).cursor());
        verify(client).probeEvents(77L, "tok-1", 12L);
        // Wire contract: the row id must be serialized under the SAME field
        // name core TaskEventRow uses ( defect: an admin mirror renamed
        // it to "id", Jackson left it null and every drawer row collapsed
        // onto one "trail-detail-null" anchor). Pin the name on the JSON.
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(rows);
        assertTrue(json.contains("\"cursor\":13"), "cursor name on the wire: " + json);
        assertFalse(json.contains("\"id\":"), "no legacy id field: " + json);
    }

    /** Keep-alive revisit doors (R67): pure pass-through on the row guard —
     *  the bridge adds no second filter, no cursor rewrite and no wording;
     *  the kind pin lives in core alone (single source, R40). */
    @Test
    void keepaliveReplayDoorsPassCursorAndLimitThroughOnTheRowGuard() {
        LoginStateView target = row(77L);
        when(client.findState(77L)).thenReturn(target);
        when(client.keepaliveEvents(77L, "ka-1", 5L)).thenReturn(List.of(
                new com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView(
                        6L, "state", "保活熔断：目标持续异常", null, "2026-11-23T10:00:00")));
        var frames = controller.keepaliveEvents(77L, "ka-1", 5L).getData();
        assertEquals(1, frames.size());
        assertEquals(6L, frames.get(0).cursor());
        verify(client).keepaliveEvents(77L, "ka-1", 5L);

        when(client.keepaliveRuns(77L, 20)).thenReturn(List.of(
                new com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView(
                        "ka-1", 3, true, "2026-11-23T09:00:00", "2026-11-23T10:00:00")));
        var runs = controller.keepaliveRuns(77L, 20).getData();
        assertEquals("ka-1", runs.get(0).runToken());
        verify(client).keepaliveRuns(77L, 20);
    }
}
