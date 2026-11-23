package com.sixbean.testpike.common.storage;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;


public class LocalStorageProvider implements StorageProvider {

 
    public static final String NAME = "local";

    private static final String PUT = "PUT";
    private static final String GET = "GET";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final Path root;
    private final String publicBaseUrl;
    private final SecretKeySpec signingKey;

    
    public LocalStorageProvider(String rootDir, String publicBaseUrl, String signSecret) {
        if (rootDir == null || rootDir.isBlank()) {
            throw new ServiceException("storage.local.root 未配置", BizErrorCode.VALIDATION_ERROR);
        }
        if (signSecret == null || signSecret.isBlank()) {
            throw new ServiceException("存储签名密钥缺失（.env STORAGE_URL_SIGN_SECRET）",
                    BizErrorCode.INTERNAL_ERROR);
        }
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl == null ? "" : trimSlashes(publicBaseUrl);
        this.signingKey = new SecretKeySpec(
                signSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new ServiceException("本地写入失败: " + e.getMessage(), BizErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public byte[] get(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            throw new ServiceException("对象不存在: " + key, BizErrorCode.NOT_FOUND);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new ServiceException("本地读取失败: " + e.getMessage(), BizErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public StorageGrant presignPut(String key, Duration ttl) {
        return new StorageGrant(key, signedUrl(PUT, key, ttl));
    }

    @Override
    public StorageGrant presignGet(String key, Duration ttl) {
        return new StorageGrant(key, signedUrl(GET, key, ttl));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new ServiceException("本地删除失败: " + e.getMessage(), BizErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public List<String> list(String prefix, int limit) {
        String norm = prefix == null ? "" : prefix;
        List<String> keys = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(k -> k.startsWith(norm))
                    .sorted(Comparator.naturalOrder())
                    .limit(Math.max(limit, 0))
                    .forEach(keys::add);
        } catch (IOException e) {
            throw new ServiceException("本地列举失败: " + e.getMessage(), BizErrorCode.INTERNAL_ERROR);
        }
        return keys;
    }

    
    public boolean verifySignedUrl(String key, long expiresEpochSec, String signature) {
        return verifySignedUrl(GET, key, expiresEpochSec, signature);
    }

    
    public boolean verifySignedUrl(String verb, String key, long expiresEpochSec, String signature) {
        if (!PUT.equals(verb) && !GET.equals(verb)) {
            return false;
        }
        if (expiresEpochSec < java.time.Instant.now().getEpochSecond()) {
            return false;
        }
        byte[] expected = sign(verb, key, expiresEpochSec).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature == null ? new byte[0] : signature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

 
    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.startsWith("/")) {
            throw new IllegalArgumentException("非法对象 key: " + key);
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("对象 key 越界: " + key);
        }
        return target;
    }

    private String signedUrl(String verb, String key, Duration ttl) {
        long exp = java.time.Instant.now().getEpochSecond()
                + (ttl == null || ttl.isZero() || ttl.isNegative() ? 900 : ttl.getSeconds());
        return publicBaseUrl + "/" + key + "?expires=" + exp + "&signature=" + sign(verb, key, exp);
    }

    private String sign(String verb, String key, long expiresEpochSec) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(signingKey);
            byte[] h = mac.doFinal((verb + "\n" + key + "\n" + expiresEpochSec)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (java.security.GeneralSecurityException e) {
            throw new ServiceException("存储 URL 签名失败: " + e.getMessage(), BizErrorCode.INTERNAL_ERROR);
        }
    }

    private static String trimSlashes(String s) {
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) == '/') {
            a++;
        }
        while (b > a && s.charAt(b - 1) == '/') {
            b--;
        }
        return s.substring(a, b);
    }
}
