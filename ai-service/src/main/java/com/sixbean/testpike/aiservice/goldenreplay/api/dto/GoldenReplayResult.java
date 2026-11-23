package com.sixbean.testpike.aiservice.goldenreplay.api.dto;

/**
 * Summary of one finished golden replay (echo of what landed on the core
 * drift ledger — the authoritative pass/block decision stays server-side in
 * core, re-derived from the pairs, never from these counts).
 *
 * @param templateDomain  family domain replayed
 * @param templateName    family name replayed
 * @param packName        golden pack (judge eval-set) consumed
 * @param packVersion     pack version consumed (pinned by snapshotDigest)
 * @param snapshotDigest  sha-256 hex fingerprint of the consumed sample set
 * @param sampleTotal     samples replayed through both versions
 * @param driftedCount    samples whose normalized output digest changed
 */
public record GoldenReplayResult(
        String templateDomain,
        String templateName,
        String packName,
        Integer packVersion,
        String snapshotDigest,
        int sampleTotal,
        int driftedCount) {
}
