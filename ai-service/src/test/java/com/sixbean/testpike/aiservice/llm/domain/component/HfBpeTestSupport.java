package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


final class HfBpeTestSupport {

    private static final Map<String, HfBpeTokenEstimator> CACHE = new ConcurrentHashMap<>();
    private static JsonNode textsNode;

    private HfBpeTestSupport() {
    }

    static HfBpeTokenEstimator qwenFull() {
        return CACHE.computeIfAbsent("qwen3",
                k -> HfBpeTokenEstimator.fromFile(repoAsset("qwen3.json"), "hf-bpe-qwen3"));
    }

    static Path repoAsset(String fileName) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("assets/tokenizers").resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("仓库词表未找到 assets/tokenizers/" + fileName);
    }

    static String sampleText(String key) {
        try {
            if (textsNode == null) {
                ObjectMapper om = new ObjectMapper();
                try (InputStream in = HfBpeTestSupport.class
                        .getResourceAsStream("/tokenizer-golden/e11-golden-texts.json")) {
                    if (in == null) {
                        throw new IllegalStateException("样本夹具缺失 e11-golden-texts.json");
                    }
                    textsNode = om.readTree(in);
                }
            }
            JsonNode n = textsNode.get("samples").get(key);
            if (n == null) {
                throw new IllegalStateException("样本键不存在: " + key);
            }
            return n.asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
