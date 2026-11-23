package com.sixbean.testpike.core.behavior.api.dto;

/**
 * One replayed sample inside a golden-set drift comparison ( second round,
 * item 4): the same fixed input run through the control template version and
 * the candidate template version of one model. Each side is carried as a
 * normalized digest of the produced output — a SHA-256 lowercase hex when the
 * replay pipeline has one, or any comparable content string otherwise. A pair
 * counts as drifted when the two normalized forms differ.
 *
 * @param sampleKey stable identity of the golden sample inside the pack
 * @param controlDigest normalized output of the control (current full-release) version
 * @param candidateDigest normalized output of the candidate (gray) version
 */
public record GoldenDriftPair(String sampleKey, String controlDigest, String candidateDigest) {
}
