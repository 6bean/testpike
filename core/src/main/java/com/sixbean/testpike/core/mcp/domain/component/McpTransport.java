package com.sixbean.testpike.core.mcp.domain.component;


public enum McpTransport {

    SSE(1, "sse"),
    STREAMABLE_HTTP(2, "streamable-http");

    private final int code;
    private final String label;

    McpTransport(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    
    public static McpTransport of(int code) {
        for (McpTransport t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return null;
    }

    
    public static String labelOf(int code) {
        McpTransport t = of(code);
        return t == null ? String.valueOf(code) : t.label();
    }

    
    public static String allLabels() {
        return values()[0].label + "/" + values()[1].label;
    }
}
