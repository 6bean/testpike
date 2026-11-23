package com.sixbean.testpike.admin.linkage.api.controller;

import com.sixbean.testpike.common.linkage.LinkageImpactInfo;
import com.sixbean.testpike.common.linkage.LinkagePendingImpacts;
import com.sixbean.testpike.admin.linkage.infrastructure.remote.client.CoreLinkageImpactClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pending-impact bridge contract . Posture mirrors the
 * record-draft bridge precedent: the owning project is resolved by core from
 * the upstream object itself; the client-supplied projectId only names the
 * viewer's segment — a foreign coordinate can never read another project's
 * ledger (segment mismatch rejects the whole call, the ledger is never
 * silently filtered at the bridge). The CoreLinkageImpactClient stand-in is
 * L2 bridge isolation only (same precedent family); the real admin→core hop
 * is exercised by the L2 core ownership tests and the L3/L4 seam.
 */
class LinkagePendingImpactBridgeTest {

    private final CoreLinkageImpactClient client = mock(CoreLinkageImpactClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final LinkagePendingImpactController controller =
            new LinkagePendingImpactController(client, guard);

    private LinkageImpactInfo row(long id, String strategy) {
        return new LinkageImpactInfo(id, "case->script", "case", 500L, 2,
                "script", 700L + id, strategy, 2, LocalDateTime.now(), null);
    }

    @Test
    void pendingQuery_usesCoreOwnershipAnchor_andViewGateOnIt() {
        when(client.pending("case", 500L)).thenReturn(new LinkagePendingImpacts(7L,
                List.of(row(1L, "grouped_draft"))));

        var view = controller.pending("case", 500L, 7L).getData();

        verify(guard).assertCanView(any(), eq(7L));
        assertEquals(1, view.impacts().size());
        assertEquals("grouped_draft", view.impacts().get(0).dispositionStrategy());
    }

    @Test
    void emptyLedger_needsNoSlice_andPassesThroughSilently() {
        when(client.pending("requirement", 9L)).thenReturn(new LinkagePendingImpacts(null, List.of()));

        var view = controller.pending("requirement", 9L, null).getData();

        assertTrue(view.impacts().isEmpty(), "zero impact stays a zero-disturbance answer");
        verify(guard, never()).assertCanView(any(), any(Long.class));
    }

    @Test
    void foreignSlice_rejectsTheWholeCall_neverRevealsForeignRows() {
        when(client.pending("case", 500L)).thenReturn(new LinkagePendingImpacts(7L,
                List.of(row(1L, "grouped_draft"))));

        ServiceException refused = assertThrows(ServiceException.class,
                () -> controller.pending("case", 500L, 8L));
        assertEquals(BizErrorCode.FORBIDDEN, refused.getCode(),
                "crafted slice = permission refusal, not a filtered list");
    }

    @Test
    void missingSlice_withRows_rejectsAskingForTheProjectContext() {
        when(client.pending("case", 500L)).thenReturn(new LinkagePendingImpacts(7L,
                List.of(row(1L, "grouped_draft"))));

        ServiceException refused = assertThrows(ServiceException.class,
                () -> controller.pending("case", 500L, null));
        assertTrue(refused.getMessage() != null && !refused.getMessage().isBlank(),
                "the refusal names what the user must do, in operating language");
    }

    // ---- draft-keyed live anchors ----

    private com.sixbean.testpike.common.linkage.LinkageDraftImpact anchor(
            long draftId, Long projectId) {
        return new com.sixbean.testpike.common.linkage.LinkageDraftImpact(
                draftId, projectId, row(draftId, "grouped_draft"));
    }

    @Test
    void byDrafts_answersThroughTheSameSliceGate() {
        when(client.byDrafts(List.of(11L, 12L))).thenReturn(
                List.of(anchor(11L, 7L), anchor(12L, 7L)));

        var view = controller.byDrafts(List.of(11L, 12L), 7L).getData();

        assertEquals(2, view.size());
        verify(guard).assertCanView(any(), eq(7L));
    }

    @Test
    void byDrafts_emptyAnswer_needsNoSlice() {
        when(client.byDrafts(List.of(99L))).thenReturn(List.of());

        var view = controller.byDrafts(List.of(99L), null).getData();

        assertTrue(view.isEmpty(), "cards with no ledger anchor stay silent");
        verify(guard, never()).assertCanView(any(), any(Long.class));
    }

    @Test
    void byDrafts_oneForeignDraft_rejectsTheWholeBatch() {
        when(client.byDrafts(List.of(11L, 12L))).thenReturn(
                List.of(anchor(11L, 7L), anchor(12L, 8L)));

        ServiceException refused = assertThrows(ServiceException.class,
                () -> controller.byDrafts(List.of(11L, 12L), 7L));
        assertEquals(BizErrorCode.FORBIDDEN, refused.getCode(),
                "a mixed batch is refused whole, never filtered at the bridge");
    }

    @Test
    void byDrafts_missingSlice_withRows_rejectsInOperatingLanguage() {
        when(client.byDrafts(List.of(11L))).thenReturn(List.of(anchor(11L, 7L)));

        ServiceException refused = assertThrows(ServiceException.class,
                () -> controller.byDrafts(List.of(11L), null));
        assertTrue(refused.getMessage() != null && !refused.getMessage().isBlank());
    }
}
