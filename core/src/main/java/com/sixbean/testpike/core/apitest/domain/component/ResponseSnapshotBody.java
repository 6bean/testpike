package com.sixbean.testpike.core.apitest.domain.component;

/**
 * One side of a run-history response compare: the extracted HTTP body plus the
 * truncation flag carried by the step snapshot (a truncated snapshot means the
 * diff covers a truncated body only — the view must say so honestly).
 *
 * @param body      response body text recovered from the step-1 snapshot
 * @param truncated true=the snapshot column exceeded the exec.snapshot.max-bytes
 *                  budget at write time
 */
public record ResponseSnapshotBody(String body, boolean truncated) {
}
