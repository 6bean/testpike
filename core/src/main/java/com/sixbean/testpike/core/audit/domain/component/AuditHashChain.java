package com.sixbean.testpike.core.audit.domain.component;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;


public final class AuditHashChain {

    
    static final String SEP = "\u0001";

    
    static final String NULL_MARK = "\u0002";

    
    public static final String CHAIN_HEAD = "0".repeat(64);

    private AuditHashChain() {
    }

    /**
 *
 * @return SHA-256 hex
 */
    public static String hash(String prevHash, String content) {
        Objects.requireNonNull(prevHash, "prevHash 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((prevHash + content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    
    public static String canonical(Long operatorId, Long projectId, String objectType, String objectId,
                                   String action, String beforeJson, String afterJson) {
        return nvl(operatorId) + SEP
                + nvl(projectId) + SEP
                + nvlStr(objectType) + SEP
                + nvlStr(objectId) + SEP
                + nvlStr(action) + SEP
                + nvlStr(beforeJson) + SEP
                + nvlStr(afterJson);
    }

    private static String nvl(Long v) {
        return v == null ? NULL_MARK : String.valueOf(v);
    }

    private static String nvlStr(String v) {
        return v == null ? NULL_MARK : v;
    }
}
