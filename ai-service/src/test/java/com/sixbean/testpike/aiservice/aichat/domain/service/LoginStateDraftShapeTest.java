package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.WriteProposal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Login-state registration proposal shape of the dialog draft emitter
 * (screenshot capture batch piece D, emitter side).
 * <p>The card proposes registering the CHECKED subset of a server-parsed
 * DevTools panel screenshot. Two anchors are mandatory and one invariant is
 * structural:
 * <p>Piece D3 refinement (checked-subset door, ): the snapshot carries
 * EVERY parsed candidate (unticked rows with {@code selected:false}) so the
 * card can render the full check table and the human can re-tick before the
 * confirm ticket is issued — zero ticks is a legal card state (its confirm
 * gate stays disabled until at least one row is ticked), no longer a reason
 * to hide the card.
 * <p>Piece F refinement (name-domain reconciliation, ): card rows are
 * rewritten onto the SERVER's staged names before minting. The model
 * transcribes names off a screenshot, so case/whitespace drift and outright
 * hallucinations happen; every such row used to trip the landing door's
 * fail-closed per-row check at confirm time, forever (the "fake usable"
 * second shape). After alignment the human authorises exactly what exists.
 * <ul>
 * <li>{@code stagingToken} — the routing credential of the transient slot
 * holding the parsed values. Without it nothing could land, so the
 * honest-absence rule drops the card.</li>
 * <li>{@code stateName} — the landing strategy fails closed on it, and a
 * card that can only ever refuse on confirmation is banned (same rule
 * as every other shape here).</li>
 * <li>Values NEVER ride the payload (piece E red line): a token value the
 * model echoes into an entry is stripped when the payload snapshot is
 * re-serialised — the ledger row and the card face only ever carry
 * display metadata plus the check list.</li>
 * </ul>
 * <p>The assembly half pins the one-key-per-kind invariant: LOGIN_STATE_WRITE
 * owns exactly one draft switch key (the table grows by whole kinds only).
 */
class LoginStateDraftShapeTest {

    private final ObjectMapper reader = new ObjectMapper();

    @Test
    void deadTranscribedPointerRehangsTheLiveStagingTokenOfThisRound() {
        // Live evidence (BD full window , leg B 17:38:16 WARN: the
        // staged-slot name domain read as unreadable/expired): the second screenshot in the SAME session
        // minted no card, because the observation block of the earlier turn
        // still shows the DEAD token of the first slot (leg A deleted it on
        // landing, R57 terminal cleanup) and the model transcribes that
        // plausible spelling. The server holds a live token THIS round and
        // never reconciled it — the R51 shape: injected evidence ×
        // transcription meeting without a single-point check. One unreadable
        // transcribed pointer + exactly one live token → re-hang the live
        // pointer and mint the card.
        java.util.Map<String, java.util.List<String>> slotRows = new java.util.HashMap<>();
        slotRows.put("slot-dead", List.of());
        slotRows.put("slot-live", List.of("auth_token", "session_id", "lang"));
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\"slot-dead\",\"stateName\":\"面板登录态\","
                        + "\"entries\":[{\"name\":\"auth_token\",\"selected\":true},"
                        + "{\"name\":\"session_id\",\"selected\":false},"
                        + "{\"name\":\"lang\",\"selected\":false}]}", reader,
                0L, slotRows::get, List.of("slot-live"));

        assertNotNull(proposal,
                "a live slot exists this round — the dead transcription must not blank the card");
        assertTrue(proposal.actionJson().contains("\"slot-live\""),
                "the payload routes to the live staging credential");
        assertFalse(proposal.actionJson().contains("slot-dead"),
                "the dead pointer never survives reconciliation");
        assertTrue(proposal.actionJson().contains("\"name\":\"auth_token\",\"selected\":true"),
                "rows align against the LIVE slot's name domain, ticks preserved");
    }

    @Test
    void deadPointerWithTwoLiveTokensStaysHonestAbsence() {
        // Fail-closed half of the bidirectional pair: guessing WHICH of two
        // live slots the model meant would route the confirm landing to rows
        // the human never saw, so zero re-hang happens and the card abstains
        // exactly as before the fix.
        java.util.Map<String, java.util.List<String>> slotRows = new java.util.HashMap<>();
        slotRows.put("slot-dead", List.of());
        slotRows.put("slot-live-a", List.of("auth_token"));
        slotRows.put("slot-live-b", List.of("lang"));
        assertNull(DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\"slot-dead\",\"stateName\":\"x\","
                        + "\"entries\":[{\"name\":\"auth_token\",\"selected\":true}]}", reader,
                0L, slotRows::get, List.of("slot-live-a", "slot-live-b")),
                "an ambiguous live domain is never guessed onto the card");
    }

    @Test
    void liveTranscribedPointerIgnoresTheRoundTokensEntirely() {
        // Zero-drift guard: when the transcription routes to a readable slot,
        // the round's live-token list changes nothing — same rows, same
        // payload as the pre-fix mint.
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\"slot-9\",\"stateName\":\"x\","
                        + "\"entries\":[{\"name\":\"auth_token\",\"selected\":true}]}", reader,
                0L, token -> List.of("auth_token"), List.of("slot-other"));

        assertNotNull(proposal);
        assertTrue(proposal.actionJson().contains("slot-9"),
                "a readable pointer is left exactly as transcribed");
        assertFalse(proposal.actionJson().contains("slot-other"),
                "live tokens never overwrite an already-valid routing anchor");
    }

    @Test
    void checkedSubsetLandsAsACardAndThePayloadCarriesNoValues() {
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\"slot-77\","
                        + "\"stateName\":\"门户日常环境登录态\","
                        + "\"entries\":[{\"name\":\"SESSION\",\"selected\":true,\"value\":\"abc123\"},"
                        + "{\"name\":\"theme\",\"selected\":false}]}", reader,
                0L, token -> List.of("SESSION", "theme"));

        assertNotNull(proposal);
        assertEquals("LOGIN_STATE_WRITE", proposal.objectType());
        assertEquals(0L, proposal.targetId(), "no ledger row exists when the card is emitted");
        assertEquals(0, proposal.baseVersion(), "registration carries no optimistic anchor");
        assertTrue(proposal.summary().contains("门户日常环境登录态"), "card names the state being filed");
        assertTrue(proposal.summary().contains("1"), "card counts the checked rows the human authorised");
        assertFalse(proposal.actionJson().contains("abc123"),
                "a value echoed by the model must never reach the payload snapshot");
        assertFalse(proposal.actionJson().contains("\"value\""),
                "the stripped payload keeps only name/selected display metadata");
        assertTrue(proposal.actionJson().contains("\"name\":\"theme\",\"selected\":false"),
                "unticked candidates ride the snapshot too — the card renders the full check table");
        assertTrue(proposal.actionJson().contains("slot-77"),
                "the routing credential survives — the landing chain reloads values from the slot");
    }

    @Test
    void missingStagingTokenGetsNoCard() {
        assertNull(DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stateName\":\"x\",\"entries\":[{\"name\":\"A\",\"selected\":true}]}", reader,
                0L, token -> List.of("A")));
    }

    @Test
    void nothingCheckedStillEmitsTheCardForTheHumanToTick() {
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\"slot-77\",\"stateName\":\"x\","
                        + "\"entries\":[{\"name\":\"A\",\"selected\":false},{\"name\":\"B\"}]}", reader,
                0L, token -> List.of("A", "B"));
        assertNotNull(proposal,
                "zero ticks is a legal card state — the confirm gate stays disabled, the card does not vanish");
        assertTrue(proposal.actionJson().contains("\"name\":\"A\",\"selected\":false"));
        assertTrue(proposal.actionJson().contains("\"name\":\"B\",\"selected\":false"));
        assertTrue(proposal.summary().contains("勾选 0"), "the card says plainly that nothing is authorised yet");
    }

    @Test
    void blankStateNameGetsNoCard() {
        assertNull(DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\"slot-77\",\"stateName\":\"  \",\"entries\":[{\"name\":\"A\",\"selected\":true}]}",
                reader, 0L, token -> List.of("A")));
    }

    @Test
    void modelTranscriptionIsRewrittenOntoTheServerStagedNameDomain() {
        AtomicReference<String> probedToken = new AtomicReference<>();
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\" slot-9 \",\"stateName\":\"门户登录态\","
                        + "\"entries\":["
                        + "{\"name\":\" Auth_Token \",\"selected\":true},"
                        + "{\"name\":\"ghost\",\"selected\":true},"
                        + "{\"name\":\"jsessionid\",\"selected\":false}]}", reader,
                0L, token -> {
                    probedToken.set(token);
                    return List.of("auth_token", "JSESSIONID");
                });

        assertNotNull(proposal, "one aligned row is enough — a ghost row must not kill the whole card");
        assertEquals("slot-9", probedToken.get(), "the probe routes by the trimmed staging anchor");
        assertTrue(proposal.actionJson().contains("\"name\":\"auth_token\",\"selected\":true"),
                "case/whitespace drift lands on the server's canonical name, tick preserved");
        assertTrue(proposal.actionJson().contains("\"name\":\"JSESSIONID\",\"selected\":false"),
                "a case-drifted name rides the card as the STAGED spelling, never the transcribed one");
        assertFalse(proposal.actionJson().contains("ghost"),
                "a hallucinated name outside the staged domain is removed, not offered for ticking");
        assertFalse(proposal.actionJson().contains("Auth_Token"),
                "the transcribed spelling never survives alignment");
        assertTrue(proposal.summary().contains("勾选 1"),
                "the checked count counts aligned rows (ghost tick dropped with its row)");
    }

    @Test
    void everyRowOutsideTheStagedDomainMeansNoCard() {
        assertNull(DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\"slot-9\",\"stateName\":\"x\","
                        + "\"entries\":[{\"name\":\"auth_token\",\"selected\":true}]}", reader,
                0L, token -> List.of("totally_other")),
                "a card with zero alignable rows offers nothing the door could ever accept");
    }

    @Test
    void expiredOrUnreadableSlotMeansNoCard() {
        assertNull(DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\"slot-9\",\"stateName\":\"x\","
                        + "\"entries\":[{\"name\":\"auth_token\",\"selected\":true}]}", reader,
                0L, token -> List.of()),
                "the staged slot is the single source of names — no slot, no honest card");
    }

    @Test
    void duplicateLookalikesCollapseOntoTheStagedNameOnce() {
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\"slot-9\",\"stateName\":\"x\","
                        + "\"entries\":[{\"name\":\"auth_token\",\"selected\":false},"
                        + "{\"name\":\"AUTH_TOKEN\",\"selected\":true}]}", reader,
                0L, token -> List.of("auth_token"));

        assertNotNull(proposal);
        assertEquals(1, countOccurrences(proposal.actionJson(), "\"name\":\"auth_token\""),
                "two transcribed spellings of one staged name must render ONE check row");
        assertTrue(proposal.actionJson().contains("\"name\":\"auth_token\",\"selected\":true"),
                "the human ticked that row under one of its spellings — the union authorises it");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void redMarkAnnotationGluedOntoTheTranscribedNameStillAligns() {
        // Live evidence (contract-guidance rerun, leg1 probe 17:08:03 WARN):
        // the staging summary renders the flagged row with the red-mark
        // annotation glued behind the name, and the model transcribes that
        // whole line as the entry name. The glued annotation used to break
        // both the folded exact hit and the unique-suffix fallback, so the
        // ONLY risk-flagged row — the row the whole journey is about — was
        // silently dropped off the card while the benign rows rode it.
        // Stripping trailing bracketed annotations from the transcription
        // side keeps the card name domain byte-exact on the staged side.
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\"slot-9\",\"stateName\":\"面板登录态\","
                        + "\"entries\":["
                        + "{\"name\":\"local_storage auth token（已标红）\",\"selected\":true},"
                        + "{\"name\":\"auth_token (已标红)\",\"selected\":true},"
                        + "{\"name\":\"local_storage mystery（已标红）\",\"selected\":true}]}", reader,
                0L, token -> List.of("auth token", "session id"));

        assertNotNull(proposal, "an annotation-glued transcription must not strip the card of every row");
        assertTrue(proposal.actionJson().contains("\"name\":\"auth token\",\"selected\":true"),
                "both annotated spellings collapse onto the one staged row, tick preserved");
        assertEquals(1, countOccurrences(proposal.actionJson(), "\"name\":\"auth token\""),
                "two annotated transcriptions of one staged name render ONE check row");
        assertFalse(proposal.actionJson().contains("mystery"),
                "annotation stripping never widens the door: an outside-domain name is still removed");
        assertFalse(proposal.actionJson().contains("已标红"),
                "the annotation is alignment noise only — it never rides the payload");
    }

    @Test
    void connectorDriftBetweenStagedOcrNamesAndTranscriptionStillAligns() {
        // OCR drops the connector off panel names ("auth_token" parses as
        // "auth token"), and the model then re-transcribes the plausible
        // underscored spelling back. Both spellings fold to one alignment
        // key, so the row rides the card — under the STAGED bytes, because
        // the landing door re-checks each name against the slot byte for
        // byte and that door must not be relaxed.
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\"slot-9\",\"stateName\":\"x\","
                        + "\"entries\":[{\"name\":\"auth_token\",\"selected\":true},"
                        + "{\"name\":\"session_id\",\"selected\":true},"
                        + "{\"name\":\"lang\",\"selected\":false}]}", reader,
                0L, token -> List.of("auth token", "session id", "lang"));

        assertNotNull(proposal, "connector drift must not strip whole rows off the card");
        assertTrue(proposal.actionJson().contains("\"name\":\"auth token\",\"selected\":true"),
                "the underscored transcription aligns onto the staged spaced spelling, tick preserved");
        assertTrue(proposal.actionJson().contains("\"name\":\"session id\",\"selected\":true"),
                "the second connector-drifted row aligns too");
        assertTrue(proposal.actionJson().contains("\"name\":\"lang\",\"selected\":false"));
        assertTrue(proposal.summary().contains("勾选 2"),
                "both folded-name ticks count toward the authorisation the human gave");
    }

    @Test
    void panelSectionPrefixFusedIntoTranscriptionStillAligns() {
        // Production traffic shape caught by the segment3 rerun ( piece F
        // three-fix): the Application panel screenshot ALWAYS carries section
        // headers, and the model transcribes them fused into the name
        // ("local_storage auth token" for the bare staged "auth_token").
        // Folded equality misses every such row, which blanked the whole card
        // four runs in a row. Fallback = unique folded suffix hit onto the
        // staged domain; the row rides under the STAGED bytes and the human
        // tick travels with it.
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\"slot-9\",\"stateName\":\"x\","
                        + "\"entries\":[{\"name\":\"local_storage auth token\",\"selected\":true},"
                        + "{\"name\":\"local_storage session id\",\"selected\":true},"
                        + "{\"name\":\"local_storage lang\",\"selected\":false}]}", reader,
                0L, token -> List.of("auth_token", "session_id", "lang"));

        assertNotNull(proposal, "a section-prefix fusion must not blank the whole card");
        assertTrue(proposal.actionJson().contains("\"name\":\"auth_token\",\"selected\":true"),
                "the fused transcription aligns onto the bare staged name, tick preserved");
        assertTrue(proposal.actionJson().contains("\"name\":\"session_id\",\"selected\":true"),
                "the second fused row aligns onto its unique staged suffix");
        assertTrue(proposal.actionJson().contains("\"name\":\"lang\",\"selected\":false"));
        assertFalse(proposal.actionJson().contains("local_storage"),
                "the fused section prefix never survives into the payload");
        assertTrue(proposal.summary().contains("勾选 2"),
                "the authorisation counts the aligned rows the human ticked");
    }

    @Test
    void ambiguousSuffixMatchIsRemovedNotGuessed() {
        // Two staged names are folded suffixes of ONE fused transcription
        // ("auth_token" and "token" both end the string). Picking either
        // would authorise a row under a spelling the human never saw, so the
        // ambiguous row fails closed — removed, never guessed — while the
        // unambiguous rows still ride the card.
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "session.login-state.register", "LOGIN_STATE_WRITE",
                "{\"stagingToken\":\"slot-9\",\"stateName\":\"x\","
                        + "\"entries\":[{\"name\":\"local_storage auth token\",\"selected\":true},"
                        + "{\"name\":\"lang\",\"selected\":false}]}", reader,
                0L, token -> List.of("auth_token", "token", "lang"));

        assertNotNull(proposal, "one unambiguous row is enough to mint the card");
        assertFalse(proposal.actionJson().contains("auth_token"),
                "the ambiguous fused row must not be guessed onto its longer candidate");
        assertFalse(proposal.actionJson().contains("\"name\":\"token\""),
                "the shorter candidate is not offered either");
        assertTrue(proposal.actionJson().contains("\"name\":\"lang\",\"selected\":false"));
        assertTrue(proposal.summary().contains("勾选 0"),
                "an ambiguous tick authorises nothing");
    }

    @Test
    void kindOwnsExactlyItsSwitchKeyInTheAssemblyTable() throws Exception {
        Field field = ChatRunOrchestrator.class.getDeclaredField("DRAFT_SWITCH_KEYS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> keys = (Map<String, String>) field.get(null);
        assertEquals("ai.chat.login-state-draft.enabled", keys.get("LOGIN_STATE_WRITE"),
                "the login-state card answers to its own hot-updatable switch key");
    }
}
