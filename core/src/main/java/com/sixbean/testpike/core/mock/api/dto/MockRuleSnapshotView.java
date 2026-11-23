package com.sixbean.testpike.core.mock.api.dto;

import java.util.List;

/**
 * Rule snapshot for one mock space — the contract the mock-server RuleLoader
 * polls (version-pinned pull model, never a push).
 * <p>{@code version} is content-pinned: it moves when any enabled rule row or
 * the space row mutates, so the loader only re-parses on real drift and can
 * fail-closed report staleness while holding the last good pin.
 *
 * @param version content pin (opaque token, compare-by-equality only)
 * @param space space header row (routing/auth/limits config)
 * @param stubs enabled rules in evaluation order (priority asc, id asc);
 * disabled rules are excluded here and now
 * @param scenarios stateful machine declarations of the space (published
 * only, same serving gate as stubs) — the traffic plane
 * steps lanes from these, never from PostgreSQL
 * @param activeRecordingId id of the space's currently open cassette box, or
 * null when no recording runs — the proxy forwarder only
 * forwards while this carries a value
 * @param generatedAt assembly time (staleness display only, never a pin input)
 */
public record MockRuleSnapshotView(
        String version,
        MockSpaceInfo space,
        List<MockStubInfo> stubs,
        List<MockScenarioInfo> scenarios,
        Long activeRecordingId,
        java.time.LocalDateTime generatedAt) {
}
