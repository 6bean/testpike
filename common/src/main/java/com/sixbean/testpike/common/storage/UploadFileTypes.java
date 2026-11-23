package com.sixbean.testpike.common.storage;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;


public final class UploadFileTypes {

    
    public static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

    
    private static final Map<String, String> MIME_EXT = ordered(
            "image/png", ".png",
            "image/jpeg", ".jpg/.jpeg",
            "image/gif", ".gif",
            "image/webp", ".webp");

    
    private static final Map<String, String> EXT_MIME = ordered(
            ".png", "image/png",
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg",
            ".gif", "image/gif",
            ".webp", "image/webp");

    private UploadFileTypes() {
    }

    
    public static String validateAttachment(String fileName, String declaredContentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new ServiceException("附件内容为空", BizErrorCode.VALIDATION_ERROR);
        }
        if (content.length > MAX_ATTACHMENT_BYTES) {
            throw new ServiceException("附件大小超过限制（上限 10MB，当前 "
                    + (content.length / 1024 / 1024) + "MB）", BizErrorCode.VALIDATION_ERROR);
        }
        String lower = fileName == null ? "" : basename(fileName).toLowerCase(Locale.ROOT);
        String byExt = EXT_MIME.entrySet().stream()
                .filter(e -> lower.endsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (byExt == null) {
            throw new ServiceException("附件格式不支持（可选：" + String.join(" / ", MIME_EXT.values()) + "）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String magic = detectMagic(content);
        if (magic == null) {
            throw new ServiceException("「" + fileName + "」不是有效的图片文件（文件内容与扩展名不符）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!magic.equals(byExt)) {
            throw new ServiceException("附件扩展名与实际图片格式不符（拒绝伪装文件）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String declared = normalizeDeclared(declaredContentType);
        if (!declared.isEmpty() && !magic.equals(declared)) {
            throw new ServiceException("附件声明格式与实际内容不符（拒绝伪装文件）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return magic;
    }

    
    private static String detectMagic(byte[] b) {
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "image/gif";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    
    private static String normalizeDeclared(String declared) {
        if (declared == null) {
            return "";
        }
        int semicolon = declared.indexOf(';');
        String v = semicolon >= 0 ? declared.substring(0, semicolon) : declared;
        return v.trim().toLowerCase(Locale.ROOT);
    }

    
    private static String basename(String fileName) {
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        return slash >= 0 ? fileName.substring(slash + 1) : fileName;
    }

    
    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }
}
