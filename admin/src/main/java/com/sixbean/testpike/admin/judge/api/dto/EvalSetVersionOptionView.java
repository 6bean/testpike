package com.sixbean.testpike.admin.judge.api.dto;

/**
 * Published evaluation-set version row for the run-creation picker
 * ( segment A; mirrors core EvalSetVersionView — same shape the publish
 * door already returns, so the run form consumes the anchor triple
 * (setName, version, snapshotDigest) without a second definition).
 *
 * @param setName evaluation-set name
 * @param version published version number
 * @param snapshotDigest frozen SHA-256 anchor of the version
 * @param sampleCount sample rows inside the version
 */
public record EvalSetVersionOptionView(String setName, Integer version,
                                       String snapshotDigest, Integer sampleCount) {
}
