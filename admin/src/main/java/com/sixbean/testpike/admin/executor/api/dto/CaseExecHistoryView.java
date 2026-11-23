package com.sixbean.testpike.admin.executor.api.dto;

import java.util.List;


public record CaseExecHistoryView(long id, Long batchId, String batchName, Integer batchType,
                                  String envVersion, int status, Integer errorType,
                                  String errorDetail, Integer aiFailureClass, String aiFailureNote,
                                  Integer durationMs,
                                  String startedAt, String finishedAt,
                                  List<ScreenshotView> screenshots, String engineCode) {
}
