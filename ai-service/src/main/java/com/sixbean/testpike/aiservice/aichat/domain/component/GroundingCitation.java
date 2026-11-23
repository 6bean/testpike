package com.sixbean.testpike.aiservice.aichat.domain.component;

/**
 * One grounding citation emitted by {@link GroundingKernel} :
 * the payload of a {@code cite} frame — document + anchor + excerpt, exactly
 * what the dialog client needs to render a clickable source that jumps to the
 * help chapter (domainCode anchor) or the knowledge document (refId).
 *
 * @param index 1-based ordinal, matching the {@code [C<n>]} marker the
 * context block uses so the answer can reference sources inline
 * @param kind source family ({@code help} / {@code knowledge})
 * @param refId source row id (citation deep-link target)
 * @param domainCode help functional domain (null for knowledge rows)
 * @param title human-readable title
 * @param excerpt window of the matched text, length-bounded, shown on the
 * citation card (never the whole document)
 * @param score final ranking score (base + lexical), for trend debugging
 * @param revision ledger revision the citation stood on (V339 versioning);
 * null for help chapters and unversioned knowledge rows
 * @param chunkId paragraph anchor carried through from the retrieval hit
 * (behaviour-kernel round-2 item 3: the card deep-links to
 * the exact knowledge chunk); null for help chapters,
 * defects and rows whose source shape has no chunk id
 */
public record GroundingCitation(int index, String kind, long refId, String domainCode,
                                String title, String excerpt, double score, Integer revision,
                                String chunkId) {

    /** Versionless shape kept for help-side and legacy call sites. */
    public GroundingCitation(int index, String kind, long refId, String domainCode,
                             String title, String excerpt, double score) {
        this(index, kind, refId, domainCode, title, excerpt, score, null, null);
    }
}
