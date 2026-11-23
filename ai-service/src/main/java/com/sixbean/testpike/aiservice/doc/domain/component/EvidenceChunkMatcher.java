package com.sixbean.testpike.aiservice.doc.domain.component;

import java.util.List;
import java.util.Map;

/**
 * AP-4(3) attributes each extracted item back to the document chunk
 * its {@code evidence} excerpt was taken from. The model is asked for a verbatim
 * excerpt but never echoes internal chunk ids, so attribution is deterministic and
 * server-side: the first injected chunk whose whitespace-normalised content contains
 * the normalised excerpt wins. Blocks without an exposed row id (F5 Map-Reduce
 * summary blocks) or an excerpt that matches nothing keep a null anchor — an honest
 * "cannot jump to source" beats a guessed one.
 */
public class EvidenceChunkMatcher {

    /**
 * Stamps {@code chunkId} onto every item map in place (null when unattributable).
 *
 * <p>: attribution is first-writer-wins — an item whose anchor is
 * already settled is never re-stamped, so a later, wider pass (whole-
 * document fallback, merge-ring replacements) can only fill blanks, never
 * overwrite a group-scoped decision.
 *
 * @param chunks the blocks actually sent to the model (original chunks carry "id")
 * @param items parsed item maps, "evidence" key may be absent or null
 */
    public void attachChunkIds(List<Map<String, Object>> chunks, List<Map<String, Object>> items) {
        if (chunks == null || chunks.isEmpty() || items == null) {
            return;
        }
        for (Map<String, Object> item : items) {
            if (item.get("chunkId") instanceof Number) {
                continue; // anchor already settled by an earlier (narrower) pass
            }
            Object evidence = item.get("evidence");
            Long chunkId = evidence == null ? null : matchChunkId(chunks, String.valueOf(evidence));
            item.put("chunkId", chunkId);
        }
    }

    /**
 * server-side attribution of one group's items against
 * excerpt-containment match runs first; a group holding exactly one
 * addressable block then anchors even a paraphrased excerpt, because the
 * producing call provably read nothing else — the block set IS the
 * evidence space. Multi-block groups keep the honest null for excerpts
 * that match nothing (the wider whole-document pass may still settle it,
 * and the landing gate holds the item back when it cannot).
 *
 * @param groupChunks the original chunks fed to this group's plane
 * @param items the item maps this group produced, stamped in place
 */
    public void attachWithinGroup(List<Map<String, Object>> groupChunks,
                                  List<Map<String, Object>> items) {
        attachChunkIds(groupChunks, items);
        if (groupChunks == null || items == null) {
            return;
        }
        Long soleChunkId = null;
        int addressable = 0;
        for (Map<String, Object> chunk : groupChunks) {
            if (chunk.get("id") instanceof Number id) {
                soleChunkId = id.longValue();
                if (++addressable > 1) {
                    return; // more than one block — no unambiguous sole source
                }
            }
        }
        if (addressable == 0) {
            return;
        }
        for (Map<String, Object> item : items) {
            if (!(item.get("chunkId") instanceof Number)) {
                item.put("chunkId", soleChunkId);
            }
        }
    }

    private Long matchChunkId(List<Map<String, Object>> chunks, String evidence) {
        String needle = normalise(evidence);
        if (needle.isEmpty()) {
            return null;
        }
        for (Map<String, Object> chunk : chunks) {
            Object id = chunk.get("id");
            if (!(id instanceof Number)) {
                continue; // summarised block or id-less payload — not anchorable
            }
            Object content = chunk.get("content");
            if (content != null && normalise(String.valueOf(content)).contains(needle)) {
                return ((Number) id).longValue();
            }
        }
        return null;
    }

    /** Strip all whitespace so line-wrap/space noise cannot break containment.
 * Public single source: the generation-chain original-text piece matches
 * excerpts with exactly this normalisation . */
    public static String normalise(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
