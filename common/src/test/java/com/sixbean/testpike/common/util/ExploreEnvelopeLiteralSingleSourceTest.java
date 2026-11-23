package com.sixbean.testpike.common.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;


class ExploreEnvelopeLiteralSingleSourceTest {

    
    private static final List<String> ENVELOPE_PACKAGES = List.of("/explore/", "/executor/", "/appauto/");

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("common/src/main/java/com/sixbean/testpike/common/util/ExploreEnvelopeContract.java"))) {
            p = p.getParent();
        }
        assertTrue(p != null, "仓库根定位失败（common 契约件不可达=对账锚丢失）");
        return p;
    }

    @Test
    void 跨模块主源码零第二处信封字面量() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String module : List.of("core", "ai-service", "admin")) {
            Path mainSrc = repoRoot().resolve(module + "/src/main/java");
            if (!Files.isDirectory(mainSrc)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(mainSrc)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    scanFile(file, violations);
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "发现契约字面量第二处定义/内联（改用 ExploreEnvelopeContract 常量或判定面）:\n"
                + String.join("\n", violations));
    }

    private static void scanFile(Path file, List<String> violations) throws Exception {
        boolean envelopeTerritory = ENVELOPE_PACKAGES.stream()
                .anyMatch(seg -> file.toString().replace('\\', '/').contains(seg));
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                continue;
            }
            check(file, i + 1, line, "\"app://\"", violations);
            check(file, i + 1, line, "\"explore-act\"", violations);
            if (envelopeTerritory) {
                check(file, i + 1, line, "\"mobile\"", violations);
            }
        }
    }

    private static void check(Path file, int lineNo, String line, String token, List<String> violations) {
        if (line.contains(token)) {
            violations.add(file.getFileName() + ":L" + lineNo + " 内联 " + token + " → " + line);
        }
    }
}
