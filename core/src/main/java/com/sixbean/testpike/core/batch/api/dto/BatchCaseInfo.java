package com.sixbean.testpike.core.batch.api.dto;


public record BatchCaseInfo(long id, long batchId, long caseId, String caseNo, String title,
                            String endType, int caseVersion, int sortIndex, int result,
                            String resultLabel, int manualSkip, int flaky) {

 
    public static String resultLabel(int result) {
        return switch (result) {
            case 1 -> "通过";
            case 2 -> "失败";
            case 3 -> "跳过";
            case 4 -> "未执行";
            default -> "未知";
        };
    }
}
