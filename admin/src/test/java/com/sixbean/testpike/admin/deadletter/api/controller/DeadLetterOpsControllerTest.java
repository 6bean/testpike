package com.sixbean.testpike.admin.deadletter.api.controller;

import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterBrowsePageView;
import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterClassCountView;
import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterLedgerRowView;
import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterQueueCountView;
import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterReplayReceiptView;
import com.sixbean.testpike.admin.deadletter.infrastructure.remote.client.RestDeadLetterCoreClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.PermissionDomain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dead-letter ops composition door contract .
 * <p>admin = authentication + verbatim composition over the three core atomic queries
 * (10-09 layering rule): no recounting, no filter rewriting, no local defaults beyond
 * paging pass-through; every door is a GET behind one registered VIEW-family
 * permission key shared with the catalog single source.
 */
class DeadLetterOpsControllerTest {

    private static final String READ_KEY = "perm:system:dead-letter-read";
    private static final String REPLAY_KEY = "perm:system:dead-letter-replay";
    private static final String VOID_KEY = "perm:system:dead-letter-void";

    private final RestDeadLetterCoreClient coreClient = mock(RestDeadLetterCoreClient.class);
    private final DeadLetterOpsController controller = new DeadLetterOpsController(coreClient);

    @Test
    void queueBacklogPassesStatusThroughAndWrapsSuccess() {
        List<DeadLetterQueueCountView> rows = List.of(new DeadLetterQueueCountView("ai-gen.dlq", 7));
        when(coreClient.queueBacklog("pending")).thenReturn(rows);

        Response<List<DeadLetterQueueCountView>> resp = controller.queueBacklog("pending");

        assertSame(rows, resp.getData(), "group counts are not recomputed at the composition layer");
        verify(coreClient).queueBacklog(eq("pending"));
    }

    @Test
    void classDistributionForwardsNullScope() {
        List<DeadLetterClassCountView> dist = List.of(new DeadLetterClassCountView("TIMEOUT", 3));
        when(coreClient.classDistribution(null, null)).thenReturn(dist);

        Response<List<DeadLetterClassCountView>> resp = controller.classDistribution(null, null);

        assertSame(dist, resp.getData());
        verify(coreClient).classDistribution(isNull(), isNull());
    }

    @Test
    void browseWrapsCorePageIntoPageView() {
        DeadLetterLedgerRowView row = new DeadLetterLedgerRowView(1L, "fp", "ai-gen.dlq",
                "ex", "rk", "{\"a\":1}", "TIMEOUT", "reason", 0, "pending",
                null, null, "2026-11-06T00:00:00Z", "2026-11-06T00:00:00Z");
        when(coreClient.browse("q", "TIMEOUT", "voided", 2, 30))
                .thenReturn(new DeadLetterBrowsePageView(List.of(row), 45));

        Response<PageView<DeadLetterLedgerRowView>> resp =
                controller.browse("q", "TIMEOUT", "voided", 2, 30);

        assertEquals(45, resp.getData().total(), "total comes from the ledger count, never page size");
        assertEquals(1, resp.getData().list().size());
    }

    @Test
    void everyDoorSitsBehindItsRegisteredKey() {
        java.util.Map<String, String> expected = java.util.Map.of(
                "queueBacklog", READ_KEY, "classDistribution", READ_KEY, "browse", READ_KEY,
                "replayOne", REPLAY_KEY, "replayByClass", REPLAY_KEY, "replayQueue", REPLAY_KEY,
                "voidLedger", VOID_KEY);
        for (var m : DeadLetterOpsController.class.getDeclaredMethods()) {
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            if (ann == null) {
                continue;
            }
            assertEquals(expected.get(m.getName()), ann.value(),
                    "door key per registered plan: " + m.getName());
            assertNotNull(PermissionCatalog.fromKey(ann.value()),
                    "door key must be registered in the catalog single source");
        }
        assertNotNull(PermissionCatalog.fromKey(READ_KEY),
                "door key must be registered in the catalog single source");
        assertEquals(PermActionFamily.VIEW, PermissionCatalog.fromKey(READ_KEY).family(),
                "ops triage view is a VIEW-family door");
        assertEquals(PermissionDomain.SYSTEM, PermissionCatalog.fromKey(READ_KEY).domain(),
                "dead-letter ops lives in the system administration domain");
        assertEquals(PermActionFamily.RUN, PermissionCatalog.fromKey(REPLAY_KEY).family(),
                "replay re-posts a booked message = RUN family");
        assertEquals(PermActionFamily.APPROVE, PermissionCatalog.fromKey(VOID_KEY).family(),
                "void is the terminal disposition with a recorded reason = APPROVE family");
    }

    @Test
    void readDoorsStayGetAndActionDoorsArePostBehindWriteKeys() {
        int gets = 0;
        int posts = 0;
        for (var m : DeadLetterOpsController.class.getDeclaredMethods()) {
            for (var ann : m.getAnnotations()) {
                String name = ann.annotationType().getSimpleName();
                if ("GetMapping".equals(name)) {
                    gets++;
                } else if ("PostMapping".equals(name)) {
                    posts++;
                } else if (name.endsWith("Mapping") && !"RequestMapping".equals(name)) {
                    throw new AssertionError("unexpected mapping shape: " + name);
                }
            }
        }
        assertEquals(3, gets, "three read doors keep their GET shape");
        assertEquals(4, posts, "replay one / by class / queue + void are the four POST doors");
    }

    @Test
    void replayOneForwardsToTheClientAndMirrorsTheReceipt() {
        DeadLetterReplayReceiptView receipt = new DeadLetterReplayReceiptView(
                7L, "fp-1", "ai-gen.dlq", "testpike.events", "ai.doc.extract", 2);
        when(coreClient.replayOne(7L, false)).thenReturn(receipt);

        assertSame(receipt, controller.replayOne(7L, false).getData(),
                "the core receipt is composed verbatim, no admin-side rewriting");
        verify(coreClient).replayOne(7L, false);
    }

    @Test
    void replayByClassForwardsClassLiteralAndFlag() {
        when(coreClient.replayByClass("ai-gen.dlq", "LLM_TIMEOUT", true)).thenReturn(3);

        assertEquals(3, controller.replayByClass("ai-gen.dlq", "LLM_TIMEOUT", true).getData());
    }

    @Test
    void replayQueueForwardsScopeAndFlag() {
        when(coreClient.replayQueue("ai-gen.dlq", false)).thenReturn(5);

        assertEquals(5, controller.replayQueue("ai-gen.dlq", false).getData());
    }

    @Test
    void voidDoorForwardsReason() {
        when(coreClient.voidLedger(9L, "实体引用无法修复，按台账口径作废")).thenReturn(true);

        assertTrue(controller.voidLedger(9L, "实体引用无法修复，按台账口径作废").getData());
    }
}
