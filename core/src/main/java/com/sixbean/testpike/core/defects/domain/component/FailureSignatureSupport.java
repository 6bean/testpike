package com.sixbean.testpike.core.defects.domain.component;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;


@Component
public class FailureSignatureSupport {

    
    private static final Pattern ISO_TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
    
    private static final Pattern UUID_PAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    
    private static final Pattern NUMBER_RUN = Pattern.compile("\\d+(\\.\\d+)?");
    
    private static final int DETAIL_CAP = 2000;

    
    public String signature(Long caseId, int failStep, Integer errorType, String errorDetail) {
        String material = caseId + "|" + failStep + "|" + errorType + "|" + normalize(errorDetail);
        return sha256Hex(material);
    }

    
    public String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String capped = text.length() > DETAIL_CAP ? text.substring(0, DETAIL_CAP) : text;
        String folded = ISO_TIMESTAMP.matcher(capped).replaceAll("#");
        folded = UUID_PAT.matcher(folded).replaceAll("#");
        return NUMBER_RUN.matcher(folded).replaceAll("#");
    }

    private String sha256Hex(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
