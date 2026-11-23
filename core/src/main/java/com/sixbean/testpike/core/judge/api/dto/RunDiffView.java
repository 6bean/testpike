package com.sixbean.testpike.core.judge.api.dto;

import java.util.List;


public record RunDiffView(Long runAId, Long runBId, String targetRefA, String targetRefB,
                          String evalsetName, Integer evalsetVersion, String rubricName,
                          List<RunDiffRowView> rows, int page, int size, long totalRows,
                          int upCount, int downCount, int flatCount,
                          int onlyACount, int onlyBCount, int comparedCount,
                          Double avgA, Double avgB, Double avgDiff,
                          double welchT, double pValue, boolean significant,
                          String significanceNote) {
}
