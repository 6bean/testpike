package com.sixbean.testpike.aiservice.goldenreplay.domain.component;

/**
 * One resolved golden pack pointer (judge eval-set name + pinned version)
 * read from the single-source mapping key.
 *
 * @param setName eval-set name inside tp_judge_sample
 * @param version eval-set version frozen as the golden snapshot
 */
public record GoldenPack(String setName, int version) {
}
