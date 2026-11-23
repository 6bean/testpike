package com.sixbean.testpike.aiservice.aichat.domain.component;

import java.time.OffsetDateTime;

/**
 * One grounding candidate handed to {@link GroundingKernel} by the retrieval
 * side : a help chapter or a knowledge-base chunk, already
 * flattened to title + text so the assembler stays a pure function and every
 * source adapter (help index over HTTP, vector search in-process) maps into
 * the same shape.
 *
 * @param kind source family, one of {@link GroundingKernel#KIND_HELP} /
 * {@link GroundingKernel#KIND_KNOWLEDGE}
 * @param refId row id of the source (help doc id / knowledge doc id) —
 * the dedup key together with kind, and the citation target
 * the client can deep-link to
 * @param domainCode help functional domain (null for knowledge rows); carried
 * into the citation so the UI can anchor-jump the chapter
 * @param title human-readable title shown in the citation card
 * @param content full text of the section/chunk (excerpt window is cut from
 * it; may be null when only a title is indexed)
 * @param baseScore retrieval-side confidence (vector score for knowledge
 * rows, 0 for help rows which rank purely lexically)
 * @param revision current ledger revision of the knowledge row (V339
 * versioning); null for help chapters and when the ledger
 * read was unavailable
 * @param managerOnly vertical reach of the row's facts (AO batch S4
 * : true when the candidate carries responsibility
 * or attribution content a plain project member must not
 * see; enforced solely by {@link GroundingKernel}
 * @param updatedAt last write time of the source row as stored by the ledger
 * (AX batch item 6: the suspected-stale grade compares
 * it against the configured window); null means the source
 * shape carries no timestamp and is never accused of staleness
 * @param chunkId vector-row id of the exact knowledge chunk behind the hit
 * (behaviour-kernel round-2 item 3: lets the citation card
 * jump to the paragraph, not just the document); null for
 * help chapters, defect rows and unchunked sources
 */
public record GroundingHit(String kind, long refId, String domainCode, String title,
                           String content, double baseScore, Integer revision,
                           boolean managerOnly, OffsetDateTime updatedAt,
                           String chunkId) {

    /** Versionless shape for help chapters and ledger-read fallbacks. */
    public GroundingHit(String kind, long refId, String domainCode, String title,
                        String content, double baseScore) {
        this(kind, refId, domainCode, title, content, baseScore, null, false, null, null);
    }

    /** Shape with the ledger revision but no manager-only reach. */
    public GroundingHit(String kind, long refId, String domainCode, String title,
                        String content, double baseScore, Integer revision) {
        this(kind, refId, domainCode, title, content, baseScore, revision, false, null, null);
    }

    /** Shape with the role reach but no ledger timestamp. */
    public GroundingHit(String kind, long refId, String domainCode, String title,
                        String content, double baseScore, Integer revision,
                        boolean managerOnly) {
        this(kind, refId, domainCode, title, content, baseScore, revision, managerOnly, null, null);
    }
}
