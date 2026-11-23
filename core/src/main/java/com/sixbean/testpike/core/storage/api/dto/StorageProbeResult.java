package com.sixbean.testpike.core.storage.api.dto;


public record StorageProbeResult(String provider, boolean success, String category,
                                 String message, String probeKey) {

    
    public static final String CATEGORY_NETWORK = "network";
    
    public static final String CATEGORY_AUTH = "auth";
    
    public static final String CATEGORY_BUCKET = "bucket";
    
    public static final String CATEGORY_UNKNOWN = "unknown";

    
    public static StorageProbeResult ok(String provider, String probeKey) {
        return new StorageProbeResult(provider, true, null,
                "连通测试成功：探针对象写入/读取/删除往返完成", probeKey);
    }

    
    public static StorageProbeResult fail(String provider, String category, String message) {
        return new StorageProbeResult(provider, false, category, message, null);
    }
}
