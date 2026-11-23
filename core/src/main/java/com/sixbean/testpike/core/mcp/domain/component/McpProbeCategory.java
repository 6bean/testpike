package com.sixbean.testpike.core.mcp.domain.component;


public enum McpProbeCategory {

    OK("ok", "连接成功"),
    NETWORK("network", "网络不可达"),
    PROTOCOL("protocol", "协议异常"),
    AUTH("auth", "鉴权被拒");

    private final String key;
    private final String label;

    McpProbeCategory(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }
}
