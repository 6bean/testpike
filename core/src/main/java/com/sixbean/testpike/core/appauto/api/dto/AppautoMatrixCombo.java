package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoMatrixCombo(String engineCode, String platformCode,
                                 String deviceKindCode, Long envProfileId) {

    
    public String dedupKey() {
        return String.valueOf(engineCode).trim().toUpperCase(java.util.Locale.ROOT)
                + "|" + String.valueOf(platformCode).trim().toUpperCase(java.util.Locale.ROOT)
                + "|" + String.valueOf(deviceKindCode).trim().toUpperCase(java.util.Locale.ROOT)
                + "|" + envProfileId;
    }
}
