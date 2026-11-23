package com.sixbean.testpike.core.document.domain.component;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class DocChunkDiff {

    
    public static final String ADD = "add";
    
    public static final String MODIFY = "modify";
    
    public static final String DELETE = "delete";

    public List<ChapterDelta> diff(Map<String, String> oldChapters, Map<String, String> newChapters) {
        Map<String, String> oldHashed = hashByChapter(oldChapters);
        Map<String, String> newHashed = hashByChapter(newChapters);
        List<ChapterDelta> deltas = new ArrayList<>();
        for (Map.Entry<String, String> entry : newHashed.entrySet()) {
            String oldHash = oldHashed.get(entry.getKey());
            if (oldHash == null) {
                deltas.add(new ChapterDelta(entry.getKey(), ADD));
            } else if (!oldHash.equals(entry.getValue())) {
                deltas.add(new ChapterDelta(entry.getKey(), MODIFY));
            }
        }
        for (String chapter : oldHashed.keySet()) {
            if (!newHashed.containsKey(chapter)) {
                deltas.add(new ChapterDelta(chapter, DELETE));
            }
        }
        return deltas;
    }

    private static Map<String, String> hashByChapter(Map<String, String> chapters) {
        Map<String, String> hashed = new LinkedHashMap<>();
        chapters.forEach((chapter, content) ->
                hashed.put(chapter, contentHash(content == null ? "" : content)));
        return hashed;
    }

    
    public static String contentHash(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(material.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
