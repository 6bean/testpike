package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <ul>
 * </ul>
 *
 */
public final class HfBpeTokenEstimator implements TokenEstimator {

    
    private static final int[] BYTE_TO_UNICODE = buildByteToUnicode();
    private static final int[] UNICODE_TO_BYTE = buildUnicodeToByte(BYTE_TO_UNICODE);

    private final String engineId;
    
    private final byte[][] idToBytes;
    
    private final Map<ByteSeq, Integer> bytesToId;
    
    private final Map<Long, Integer> mergeRanks;
    
    private final Map<Long, Integer> pairToMerged;
    private final int mergeCount;
    
    private final Map<ByteSeq, int[]> segmentCache = new ConcurrentHashMap<>();

    private HfBpeTokenEstimator(String engineId, byte[][] idToBytes, Map<ByteSeq, Integer> bytesToId,
                                Map<Long, Integer> mergeRanks, Map<Long, Integer> pairToMerged,
                                int mergeCount) {
        this.engineId = engineId;
        this.idToBytes = idToBytes;
        this.bytesToId = bytesToId;
        this.mergeRanks = mergeRanks;
        this.pairToMerged = pairToMerged;
        this.mergeCount = mergeCount;
    }

 
    public static HfBpeTokenEstimator fromFile(Path tokenizerJson, String engineId) {
        if (!Files.exists(tokenizerJson)) {
            throw new IllegalStateException("HF tokenizer vocabulary missing, refusing to start a falsely-green estimator: " + tokenizerJson);
        }
        try (InputStream in = Files.newInputStream(tokenizerJson)) {
            return load(new ObjectMapper().readTree(in), engineId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    
    public static HfBpeTokenEstimator fromResource(String classpathResource, String engineId) {
        try (InputStream in = HfBpeTokenEstimator.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("HF tokenizer vocabulary resource missing: " + classpathResource);
            }
            return load(new ObjectMapper().readTree(in), engineId);
        } catch (IOException e) {
            throw new IllegalStateException("HF tokenizer vocabulary load failed: " + classpathResource, e);
        }
    }

    private static HfBpeTokenEstimator load(JsonNode root, String engineId) {
        JsonNode model = root.path("model");
        if (!"BPE".equals(model.path("type").asText())) {
            throw new IllegalStateException("E11 supports only model.type=BPE (actual="
                    + model.path("type").asText() + ") ; true character-level SentencePiece support is deferred to the sp-char plan");
        }
        JsonNode vocab = model.path("vocab");
        if (!vocab.isObject() || vocab.isEmpty()) {
            throw new IllegalStateException("tokenizer.json missing model.vocab");
        }
        int vocabSize = vocab.size();
        byte[][] idToBytes = new byte[vocabSize][];
        Map<ByteSeq, Integer> bytesToId = new HashMap<>(vocabSize * 2);
        var it = vocab.fields();
        while (it.hasNext()) {
            var e = it.next();
            int id = e.getValue().asInt();
            if (id < 0 || id >= vocabSize) {
                throw new IllegalStateException("vocab id out of range: " + id + " vocabSize=" + vocabSize);
            }
            byte[] bytes = tokenToBytes(e.getKey());
            idToBytes[id] = bytes;
            bytesToId.putIfAbsent(new ByteSeq(bytes), id);
        }
        Map<Long, Integer> ranks = new HashMap<>(1 << 18);
        Map<Long, Integer> merged = new HashMap<>(1 << 18);
        int merges = 0;
        for (JsonNode m : model.path("merges")) {
            String a;
            String b;
            if (m.isArray()) {
                a = m.get(0).asText();
                b = m.get(1).asText();
            } else {
                String s = m.asText();
                int sp = s.indexOf(' ');
                if (sp < 0) {
                    continue;
                }
                a = s.substring(0, sp);
                b = s.substring(sp + 1);
            }
            merges++;
            Integer ia = bytesToId.get(new ByteSeq(tokenToBytes(a)));
            Integer ib = bytesToId.get(new ByteSeq(tokenToBytes(b)));
            if (ia == null || ib == null) {
                continue;
            }
            long key = pairKey(ia, ib);
            ranks.putIfAbsent(key, merges);
            merged.putIfAbsent(key, bytesToId.get(new ByteSeq(concat(idToBytes[ia], idToBytes[ib]))));
        }
        return new HfBpeTokenEstimator(engineId, idToBytes, bytesToId, ranks, merged, merges);
    }

    @Override
    public String engineId() {
        return engineId;
    }

    
    public int vocabSize() {
        return idToBytes.length;
    }

    
    public int mergeCount() {
        return mergeCount;
    }

    
    public List<Integer> encode(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (byte[] seg : preSplitBytes(text)) {
            for (int id : encodeSegment(seg)) {
                out.add(id);
            }
        }
        return out;
    }

    @Override
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (byte[] seg : preSplitBytes(text)) {
            total += encodeSegment(seg).length;
        }
        return total;
    }

    private int[] encodeSegment(byte[] seg) {
        ByteSeq key = new ByteSeq(seg);
        int[] cached = segmentCache.get(key);
        if (cached != null) {
            return cached;
        }
        int[] ids = bpe(seg);
        if (segmentCache.size() < 500_000) {
            segmentCache.put(key, ids);
        }
        return ids;
    }


    /**
 * 1 (?i:'s|'t|'re|'ve|'m|'ll|'d) 2 [^\r\n\p{L}\p{N}]?\p{L}+ 3 \p{N}
 * 4 [^\s\p{L}\p{N}]+[\r\n]* 5 \s*[\r\n]+ 6 \s+(?!\S) 7 \s+
 */
    static List<byte[]> preSplitBytes(String text) {
        List<byte[]> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            int branchEnd = matchBranch(text, i, n);
            if (branchEnd < 0) {
                int cp = text.codePointAt(i);
                buf.appendCodePoint(cp);
                i += Character.charCount(cp);
                continue;
            }
            if (buf.length() > 0) {
                out.add(utf8(buf.toString()));
                buf.setLength(0);
            }
            out.add(utf8(text.substring(i, branchEnd)));
            i = branchEnd;
        }
        if (buf.length() > 0) {
            out.add(utf8(buf.toString()));
        }
        return out;
    }

    /**
 * 2 [^\r\n\p{L}\p{N}]?\p{L}+ 3 \p{N} 4 ?[^\s\p{L}\p{N}]+[\r\n]*
 */
    private static int matchBranch(String t, int i, int n) {
        char c0 = t.charAt(i);
        if (c0 == '\'') {
            for (int len = 2; len >= 1; len--) {
                if (i + 1 + len <= n && isContraction(t, i + 1, i + 1 + len)) {
                    return i + 1 + len;
                }
            }
            return -1;
        }
        if (c0 != '\r' && c0 != '\n' && !isLetter(c0) && !isDigitChar(c0)) {
            int j = i + charAdvance(t, i);
            if (j < n && isLetter(t.charAt(j))) {
                while (j < n && isLetter(t.charAt(j))) {
                    j += charAdvance(t, j);
                }
                return j;
            }
        }
        if (isLetter(c0)) {
            int j = i;
            while (j < n && isLetter(t.charAt(j))) {
                j += charAdvance(t, j);
            }
            return j;
        }
        if (isDigitChar(c0)) {
            return i + charAdvance(t, i);
        }
        if (c0 == ' ') {
            int j = i + 1;
            if (j < n && isSymbolChar(t.charAt(j))) {
                return runTrailingNewlines(t, runSymbol(t, j, n), n);
            }
        } else if (isSymbolChar(c0)) {
            return runTrailingNewlines(t, runSymbol(t, i, n), n);
        }
        if (isWhitespaceChar(c0)) {
            int j = i;
            int lastNl = -1;
            while (j < n && isWhitespaceChar(t.charAt(j))) {
                if (t.charAt(j) == '\r' || t.charAt(j) == '\n') {
                    lastNl = j;
                }
                j++;
            }
            if (lastNl >= 0) {
                return lastNl + 1;
            }
            if (j < n && j - i >= 2) {
                return j - 1;
            }
            return j;
        }
        return -1;
    }

    private static int runSymbol(String t, int i, int n) {
        int j = i;
        while (j < n && isSymbolChar(t.charAt(j))) {
            j += charAdvance(t, j);
        }
        return j;
    }

    private static int runTrailingNewlines(String t, int i, int n) {
        int j = i;
        while (j < n && (t.charAt(j) == '\r' || t.charAt(j) == '\n')) {
            j++;
        }
        return j;
    }

    private static int charAdvance(String t, int i) {
        return Character.isHighSurrogate(t.charAt(i)) && i + 1 < t.length()
                && Character.isLowSurrogate(t.charAt(i + 1)) ? 2 : 1;
    }

    
    private static boolean isLetter(char c) {
        return Character.isLetter(c);
    }

    
    private static boolean isDigitChar(char c) {
        return Character.isDigit(c);
    }

    
    private static boolean isWhitespaceChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || Character.isWhitespace(c);
    }

    
    private static boolean isSymbolChar(char c) {
        return !isWhitespaceChar(c) && !isLetter(c) && !isDigitChar(c);
    }

    private static boolean isContraction(String t, int from, int to) {
        if (to - from > 2) {
            return false;
        }
        String frag = t.substring(from, to).toLowerCase();
        return switch (frag) {
            case "s", "t", "re", "ve", "m", "ll", "d" -> true;
            default -> false;
        };
    }


    private int[] bpe(byte[] seg) {
        int[] ids = new int[seg.length * 2 + 2];
        int len = 0;
        for (byte b : seg) {
            Integer id = bytesToId.get(new ByteSeq(new byte[]{b}));
            if (id == null) {
                throw new IllegalStateException("vocabulary missing single-byte token 0x" + Integer.toHexString(b & 0xFF));
            }
            ids[len++] = id;
        }
        while (len > 1) {
            long bestKey = -1;
            int bestRank = Integer.MAX_VALUE;
            for (int i = 0; i < len - 1; i++) {
                long k = pairKey(ids[i], ids[i + 1]);
                Integer r = mergeRanks.get(k);
                if (r != null && r < bestRank) {
                    bestRank = r;
                    bestKey = k;
                    if (r == 0) {
                        break;
                    }
                }
            }
            if (bestKey < 0) {
                break;
            }
            Integer mergedId = pairToMerged.get(bestKey);
            if (mergedId == null) {
                break;
            }
            int[] parts = splitPairKey(bestKey);
            int a = parts[0];
            int b = parts[1];
            int w = 0;
            for (int i = 0; i < len; i++) {
                if (i + 1 < len && ids[i] == a && ids[i + 1] == b) {
                    ids[w++] = mergedId;
                    i++;
                } else {
                    ids[w++] = ids[i];
                }
            }
            len = w;
        }
        return Arrays.copyOf(ids, len);
    }


    
    static byte[] tokenToBytes(String token) {
        byte[] out = new byte[token.length()];
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            int b = c < UNICODE_TO_BYTE.length ? UNICODE_TO_BYTE[c] : -1;
            if (b < 0) {
                throw new IllegalStateException("token character not in the GPT-2 byte table: U+" + Integer.toHexString(c));
            }
            out[i] = (byte) b;
        }
        return out;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static long pairKey(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    private static int[] splitPairKey(long key) {
        return new int[]{(int) (key >> 32), (int) key};
    }

    private static int[] buildByteToUnicode() {
        int[] map = new int[256];
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if ((b >= 33 && b <= 126) || (b >= 161 && b <= 172) || (b >= 174 && b <= 255)) {
                map[b] = b;
            } else {
                map[b] = 256 + n;
                n++;
            }
        }
        return map;
    }

    private static int[] buildUnicodeToByte(int[] b2u) {
        int max = 0;
        for (int v : b2u) {
            max = Math.max(max, v);
        }
        int[] inv = new int[max + 1];
        Arrays.fill(inv, -1);
        for (int b = 0; b < 256; b++) {
            inv[b2u[b]] = b;
        }
        return inv;
    }

    
    private static final class ByteSeq {
        private final byte[] bytes;
        private final int hash;

        ByteSeq(byte[] bytes) {
            this.bytes = bytes;
            this.hash = Arrays.hashCode(bytes);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ByteSeq other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
