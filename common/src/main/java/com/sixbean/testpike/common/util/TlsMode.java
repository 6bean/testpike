package com.sixbean.testpike.common.util;


public enum TlsMode {

    
    STRICT,

    
    CUSTOM_CA,

    
    INSECURE;

    public static final String RAW_STRICT = "strict";
    public static final String RAW_CUSTOM_CA = "custom_ca";
    public static final String RAW_INSECURE = "insecure";

    
    public static TlsMode fromRaw(String raw) {
        if (raw == null) {
            return STRICT;
        }
        return switch (raw.trim().toLowerCase()) {
            case RAW_CUSTOM_CA -> CUSTOM_CA;
            case RAW_INSECURE -> INSECURE;
            default -> STRICT;
        };
    }

    public String raw() {
        return name().toLowerCase();
    }
}
