package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.DraftGroupApplyItem;
import com.sixbean.testpike.core.aichat.api.dto.DraftGroupApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group confirmation door : one
 * assistant answer that minted several cards is confirmed as a GROUP with a
 * single ticket — the human checks a subset (or all) and one apply lands the
 * write set in ONE transaction. Proposals whose anchor moved under them are
 * judged before any write: disjoint ones silently re-anchor and land, truly
 * colliding ones turn into the stale one-click-regenerate card and are named
 * in the receipt (object name, both versions, the exit) instead of dragging
 * the rest of the group down.
 * <p>Guard order is the SAME single door as the per-draft chain (red line 6):
 * rows exist and belong to this answer -> session ownership -> pending ->
 * ticket (one-shot GETDEL bound to the message id; a stale-proposal refusal
 * still burns the ticket) -> anchor adjudication -> landing -> unified
 * sibling renewal once per touched target (never per item, so a group's own
 * cards cannot double-flip — recon pitfall 14).
 * <p>Real chain, zero mocks: cards are minted through the shared frame
 * ledger onto ONE message, tickets are real Redis one-shots, writes land on
 * the real dev database.
 */
@SpringBootTest(classes = CoreApplication.class)
class GroupDraftConfirmationTest {

    @Autowired
    private DraftConfirmationService confirmationService;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private DraftCardMinting minting;

    @Autowired
    private DraftLedgerCleanup cleanup;

    @Autowired
    private CaseService caseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tag;
    private long projectId;
    private long userId;
    private long sessionId;
    private final java.util.List<Long> createdCaseIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        tag = "grpdoor" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
        ChatSessionInfo session = sessionService.create(projectId, userId, "Group door " + tag,
                1, "{\"route\":\"/cases\"}");
        sessionId = session.id();
    }

    @AfterEach
    void tearDown() {
        cleanup.sessionChain(sessionId);
        cleanup.caseCascade(createdCaseIds);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
    }

    private long seedCase(String title) {
        CaseInfo created = caseService.create(userId, new CaseCreateInput(projectId, title,
                "pre", "expect", 1, 2, "web", null, 2, 1, null, List.of()));
        createdCaseIds.add(created.id());
        return created.id();
    }

    private Map<String, Object> draftRow(long draftId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, base_version, status_note FROM tp_ai_chat_draft WHERE id = ?",
                draftId);
    }

    private int statusOf(long draftId) {
        return ((Number) draftRow(draftId).get("status")).intValue();
    }

    private DraftGroupApplyItem itemOf(DraftGroupApplyResult result, long draftId) {
        return result.items().stream()
                .filter(i -> i.draftId() == draftId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("receipt must name draft " + draftId));
    }

    @Test
    void fullAcceptLandsEveryCheckedCardUnderOneGroupTicket() {
        long caseA = seedCase("group door A " + tag);
        long caseB = seedCase("group door B " + tag);
        long message = minting.openAssistantMessage(sessionId);
        long cardA = minting.caseWriteDraftInMessage(sessionId, message, caseA,
                caseService.get(caseA).version(), "{\"title\":\"group-landed A\"}");
        long cardB = minting.caseWriteDraftInMessage(sessionId, message, caseB,
                caseService.get(caseB).version(), "{\"precondition\":\"group-landed pre\"}");

        DraftTicket ticket = confirmationService.issueGroupConfirmToken(userId, message);
        assertNotNull(ticket.token());
        DraftGroupApplyResult result = confirmationService.applyGroup(
                userId, message, ticket.token(), List.of(cardA, cardB));

        assertEquals(2, result.items().size(), "the receipt names every checked card");
        assertEquals("landed", itemOf(result, cardA).outcome());
        assertEquals("landed", itemOf(result, cardB).outcome());
        assertEquals(2, statusOf(cardA));
        assertEquals(2, statusOf(cardB));
        assertEquals("group-landed A", caseService.get(caseA).title());
        assertEquals("group-landed pre", caseService.get(caseB).precondition());
        assertEquals(cardA, itemOf(result, cardA).draftId());
        assertEquals(caseA, itemOf(result, cardA).targetId().longValue(),
                "the receipt line points at the entity the proposal wrote");
        assertNotNull(itemOf(result, cardA).objectName(), "the receipt names the target object");
    }

    @Test
    void subsetSelectionLandsOnlyCheckedCardsAndLeavesTheRestPending() {
        long caseA = seedCase("group subset A " + tag);
        long caseB = seedCase("group subset B " + tag);
        long message = minting.openAssistantMessage(sessionId);
        long cardA = minting.caseWriteDraftInMessage(sessionId, message, caseA,
                caseService.get(caseA).version(), "{\"title\":\"subset A\"}");
        long cardB = minting.caseWriteDraftInMessage(sessionId, message, caseB,
                caseService.get(caseB).version(), "{\"title\":\"subset B\"}");

        DraftTicket ticket = confirmationService.issueGroupConfirmToken(userId, message);
        DraftGroupApplyResult result = confirmationService.applyGroup(
                userId, message, ticket.token(), List.of(cardA));

        // The receipt answers the WHOLE answer, not only the checked subset:
        // a card the human left unticked is named with its own line saying it
        // stayed pending (IT-ITR-05 "never silently dropped" — otherwise the
        // human cannot tell "kept for later" from "lost inside the group").
        assertEquals(2, result.items().size(),
                "every card of the answer is answered line by line, checked or not");
        DraftGroupApplyItem kept = itemOf(result, cardB);
        assertEquals("not-ticked", kept.outcome());
        assertEquals(cardB, kept.draftId());
        assertEquals(caseB, kept.targetId().longValue(),
                "the kept line points at the entity this card would have written");
        assertNull(kept.appliedVersion(), "an unticked card produced no version");
        assertEquals("group subset B " + tag, kept.objectName(),
                "the kept line names the target object");
        assertNotNull(kept.detail(), "the kept line explains itself");
        assertTrue(kept.detail().contains("未勾选"), "the kept line says why: " + kept.detail());
        assertTrue(kept.detail().contains("待确认"),
                "the kept line says what remains to do: " + kept.detail());
        assertEquals(2, statusOf(cardA));
        assertEquals(1, statusOf(cardB), "an unchecked card stays pending, untouched");
        assertEquals("subset A", caseService.get(caseA).title());
        assertEquals("group subset B " + tag, caseService.get(caseB).title());
    }

    @Test
    void collidingCardTurnsStaleAndNamedWhileTheRestOfTheGroupStillLands() {
        long caseA = seedCase("group collide A " + tag);
        long caseB = seedCase("group collide B " + tag);
        long message = minting.openAssistantMessage(sessionId);
        long cardA = minting.caseWriteDraftInMessage(sessionId, message, caseA,
                caseService.get(caseA).version(), "{\"title\":\"card A title\"}");
        long cardB = minting.caseWriteDraftInMessage(sessionId, message, caseB,
                caseService.get(caseB).version(), "{\"title\":\"card B title\"}");

        // A human edits caseA's title directly (through the case CRUD, not the
        // draft door) between the answer and the confirmation: the anchor of
        // card A moved and the SAME field intersects — a true collision.
        int base = caseService.get(caseA).version();
        caseService.update(userId, caseA, new CaseUpdateInput("external title", null, null,
                null, null, null, null, null, null, null, null, null, null), base);
        int current = caseService.get(caseA).version();
        assertEquals(base + 1, current);

        DraftTicket ticket = confirmationService.issueGroupConfirmToken(userId, message);
        DraftGroupApplyResult result = confirmationService.applyGroup(
                userId, message, ticket.token(), List.of(cardA, cardB));

        DraftGroupApplyItem refused = itemOf(result, cardA);
        assertEquals("stale-excluded", refused.outcome(),
                "a colliding card is removed from the write set, named, never silently kept");
        assertNotNull(refused.detail());
        assertTrue(refused.detail().contains("external title"),
                "the refusal names the target object: " + refused.detail());
        assertTrue(refused.detail().contains(String.valueOf(base))
                        && refused.detail().contains(String.valueOf(current)),
                "the refusal names both versions: " + refused.detail());
        assertTrue(refused.detail().contains("重新生成"),
                "the refusal points at the one-click exit: " + refused.detail());
        assertEquals(4, statusOf(cardA), "the colliding card turns into the stale state");
        assertEquals("external title", caseService.get(caseA).title(),
                "the excluded card wrote nothing");

        assertEquals("landed", itemOf(result, cardB).outcome(),
                "the rest of the group lands as usual (per 16: excluded card named, rest normal)");
        assertEquals(2, statusOf(cardB));
        assertEquals("card B title", caseService.get(caseB).title());
    }

    @Test
    void replayedGroupTicketOpensNothingTwice() {
        long caseA = seedCase("group replay A " + tag);
        long message = minting.openAssistantMessage(sessionId);
        long cardA = minting.caseWriteDraftInMessage(sessionId, message, caseA,
                caseService.get(caseA).version(), "{\"title\":\"replay A\"}");

        DraftTicket ticket = confirmationService.issueGroupConfirmToken(userId, message);
        confirmationService.applyGroup(userId, message, ticket.token(), List.of(cardA));
        int landedVersion = caseService.get(caseA).version();

        assertThrows(ServiceException.class, () -> confirmationService.applyGroup(
                userId, message, ticket.token(), List.of(cardA)),
                "the group ticket is one-shot (GETDEL): a replay cannot open the door again");
        assertEquals(landedVersion, caseService.get(caseA).version(),
                "a replayed ticket wrote nothing");
    }

    @Test
    void selectionOutsideTheAnswerIsRefusedWithoutBurningTheTicket() {
        long caseA = seedCase("group shape A " + tag);
        long caseB = seedCase("group shape B " + tag);
        long message1 = minting.openAssistantMessage(sessionId);
        long card1 = minting.caseWriteDraftInMessage(sessionId, message1, caseA,
                caseService.get(caseA).version(), "{\"title\":\"shape card 1\"}");
        long message2 = minting.openAssistantMessage(sessionId);
        long card2 = minting.caseWriteDraftInMessage(sessionId, message2, caseB,
                caseService.get(caseB).version(), "{\"title\":\"shape card 2\"}");

        DraftTicket ticket = confirmationService.issueGroupConfirmToken(userId, message1);
        assertThrows(ServiceException.class, () -> confirmationService.applyGroup(
                userId, message1, ticket.token(), List.of(card1, card2)),
                "a checked id that does not belong to this answer's pending cards is a shape error");
        assertEquals(1, statusOf(card1), "the refused apply wrote nothing");
        assertEquals(1, statusOf(card2), "the other answer's card is untouched");

        // The shape refusal happened BEFORE the one-shot consume: the same
        // ticket still opens the door for the correct selection.
        DraftGroupApplyResult result = confirmationService.applyGroup(
                userId, message1, ticket.token(), List.of(card1));
        assertEquals("landed", itemOf(result, card1).outcome());
        assertEquals(1, statusOf(card2), "the second answer's card was never in this group");
    }

    @Test
    void foreignOperatorCannotOpenTheGroupDoor() {
        long caseA = seedCase("group foreign A " + tag);
        long message = minting.openAssistantMessage(sessionId);
        minting.caseWriteDraftInMessage(sessionId, message, caseA,
                caseService.get(caseA).version(), "{\"title\":\"foreign attempt\"}");
        long otherUserId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag + "oth", tag + "oth");
        try {
            assertThrows(ServiceException.class, () -> confirmationService.issueGroupConfirmToken(
                    otherUserId, message),
                    "issuing a group ticket for another human's answer is refused (40301)");
            DraftTicket ticket = confirmationService.issueGroupConfirmToken(userId, message);
            assertThrows(ServiceException.class, () -> confirmationService.applyGroup(
                    otherUserId, message, ticket.token(), List.of(
                            jdbcTemplate.queryForObject(
                                    "SELECT id FROM tp_ai_chat_draft WHERE message_id = ?",
                                    Long.class, message))),
                    "applying with a valid ticket still re-checks session ownership");
            assertEquals(1, statusOf(jdbcTemplate.queryForObject(
                            "SELECT id FROM tp_ai_chat_draft WHERE message_id = ?",
                            Long.class, message)),
                    "the foreign attempts wrote nothing");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", otherUserId);
        }
    }
}
