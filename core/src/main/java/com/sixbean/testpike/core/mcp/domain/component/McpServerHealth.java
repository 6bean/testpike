package com.sixbean.testpike.core.mcp.domain.component;


public enum McpServerHealth {

    UNTESTED(0, "未测试"),
    HEALTHY(1, "健康"),
    FAILED(2, "失败");

    private final int code;
    private final String label;

    McpServerHealth(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    
    public static String labelOf(int code) {
        for (McpServerHealth h : values()) {
            if (h.code == code) {
                return h.label;
            }
        }
        return String.valueOf(code);
    }
}
