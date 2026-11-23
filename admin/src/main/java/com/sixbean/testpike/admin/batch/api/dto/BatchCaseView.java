package com.sixbean.testpike.admin.batch.api.dto;


public record BatchCaseView(long id, long batchId, long caseId, String caseNo, String title,
                            String endType, int caseVersion, int sortIndex, int result,
                            String resultLabel, int manualSkip, int flaky) {
}
