package com.sixbean.testpike.common.aichat;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reference-chip contract of the dialog stream :
 * @-mentioned objects ride admin → core → broker as (kind, id, title) triples.
 * Canonicalisation is the SINGLE validation point — admin and ai-service never
 * keep a second copy — so the rules here are the whole story: a known kind,
 * a positive id, a trimmed non-blank title bounded to the platform title
 * column width, dedup by (kind, id) keeping the first arrival, and a count
 * cap enforced through the caller (core reads the K8 key and passes maxCount;
 * exceeding it is a named refusal, never a silent drop).
 */
class ChatObjectRefsTest {

    /**
 * AO batch stage 1: the @-mention palette widens the payload channel
 * with the skill / tool families. The candidate list the composer offers is
 * the core assembly set itself, so canonicalisation must accept the same
 * kinds — a chip the user could pick but the wire refuses would be a
 * contract split between the two ends.
 */
    @Test
    void skillAndToolChipsAreCanonicalAcceptedFamilies() {
        List<ChatObjectRef> refs = ChatObjectRefs.canonicalize(List.of(
                new ChatObjectRef("skill", 3L, "接口评审规范"),
                new ChatObjectRef("tool", 9L, "订单查询接口")), 10);
        assertEquals(2, refs.size());
        assertEquals("skill", refs.get(0).kind());
        assertEquals("tool", refs.get(1).kind());
    }

    @Test
    void unknownKindRefusalListsTheWholeSupportedFamilyIncludingSkillAndTool() {
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> ChatObjectRefs.canonicalize(
                        List.of(new ChatObjectRef("widget", 1L, "控件")), 10));
        assertTrue(refusal.getMessage().contains("skill"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("tool"), refusal.getMessage());
    }

    @Test
    void emptyOrNullInputCanonicalisesToEmptyList() {
        assertEquals(List.of(), ChatObjectRefs.canonicalize(null, 10));
        assertEquals(List.of(), ChatObjectRefs.canonicalize(List.of(), 10));
    }

    @Test
    void titlesAreTrimmedAndBoundedToThePlatformTitleWidth() {
        String longTitle = "标".repeat(300);
        List<ChatObjectRef> refs = ChatObjectRefs.canonicalize(
                List.of(new ChatObjectRef("case", 7L, "  登录用例  ")), 10);
        assertEquals("登录用例", refs.get(0).title());

        List<ChatObjectRef> bounded = ChatObjectRefs.canonicalize(
                List.of(new ChatObjectRef("doc", 8L, longTitle)), 10);
        assertEquals(ChatObjectRefs.TITLE_MAX_CHARS, bounded.get(0).title().length(),
                "a title longer than the column is cut, never refused outright");
    }

    @Test
    void duplicateKindIdPairsCollapseKeepingTheFirstArrival() {
        List<ChatObjectRef> refs = ChatObjectRefs.canonicalize(List.of(
                new ChatObjectRef("case", 7L, "首个标题"),
                new ChatObjectRef("case", 7L, "重复标题"),
                new ChatObjectRef("case", 8L, "另一个用例")), 10);
        assertEquals(2, refs.size());
        assertEquals("首个标题", refs.get(0).title());
        assertEquals(8L, refs.get(1).id());
    }

    @Test
    void unknownKindIsRefusedByNameNotSilentlyDropped() {
        ServiceException refusal = assertThrows(ServiceException.class, () ->
                ChatObjectRefs.canonicalize(List.of(new ChatObjectRef("secret", 1L, "x")), 10));
        assertTrue(refusal.getMessage().contains("secret"), refusal.getMessage());
    }

    @Test
    void nonPositiveIdAndBlankTitleAreRefused() {
        assertThrows(ServiceException.class,
                () -> ChatObjectRefs.canonicalize(List.of(new ChatObjectRef("case", 0L, "x")), 10));
        assertThrows(ServiceException.class,
                () -> ChatObjectRefs.canonicalize(List.of(new ChatObjectRef("case", 1L, "  ")), 10));
    }

    @Test
    void exceedingTheCountCapIsANamedRefusalNeverATruncation() {
        List<ChatObjectRef> twelve = java.util.stream.LongStream.rangeClosed(1, 12)
                .mapToObj(i -> new ChatObjectRef("case", i, "用例" + i))
                .toList();
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> ChatObjectRefs.canonicalize(twelve, 10));
        assertTrue(refusal.getMessage().contains("12"), refusal.getMessage());
    }

    @Test
    void everyAdjudicatedKindPasses() {
        for (String kind : List.of("case", "defect", "batch", "doc")) {
            assertEquals(1, ChatObjectRefs.canonicalize(
                    List.of(new ChatObjectRef(kind, 5L, "对象")), 10).size(), kind);
        }
    }
}
