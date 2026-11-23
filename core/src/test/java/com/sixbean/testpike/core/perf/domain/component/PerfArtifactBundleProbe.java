package com.sixbean.testpike.core.perf.domain.component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Test-side reader for the compiled load-artifact zip bundle .
 * <p>The adapters' compile handle moved from a temp-directory path to a
 * project-routed object-storage key, so golden-source assertions can no longer
 * use {@code Files.readString(Path.of(handle))}. This probe pulls the bundle
 * bytes through the same ArtifactStorage read route the production write used
 * (read/write same route = a broken route fails here, not silently) and gives
 * the tests entry maps and materialized directories for real-engine probes
 * (k6 inspect needs an on-disk script path next to its dataset sidecar).
 */
public final class PerfArtifactBundleProbe {

    private PerfArtifactBundleProbe() {
    }

    /** Unzip bundle bytes into an ordered entry map (zip-relative name → bytes). */
    public static Map<String, byte[]> entries(byte[] zipBytes) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(
                new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                buf.reset();
                zin.transferTo(buf);
                out.put(entry.getName(), buf.toByteArray());
            }
        }
        return out;
    }

    /** Materialize a bundle into a fresh temp directory and return that directory. */
    public static Path materialize(String bundleName, byte[] zipBytes) throws IOException {
        Path dir = Files.createTempDirectory("tp-perf-bundle-" + bundleName + "-");
        for (Map.Entry<String, byte[]> e : entries(zipBytes).entrySet()) {
            Path target = dir.resolve(e.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, e.getValue());
        }
        return dir;
    }

    /** UTF-8 text of one bundle entry (golden-script string assertions). */
    public static String textOf(Map<String, byte[]> entries, String suffix) {
        return entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith(suffix))
                .map(e -> new String(e.getValue(), StandardCharsets.UTF_8))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "bundle has no entry ending with '" + suffix + "'; entries="
                                + entries.keySet()));
    }
}
