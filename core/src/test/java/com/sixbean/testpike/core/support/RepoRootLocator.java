package com.sixbean.testpike.core.support;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Single source for locating the repository root from a test working
 * directory. Surefire runs with the module directory as cwd, so the root is
 * the first ancestor holding both the core and ai-service module folders.
 * Shared by tests that read cross-module source or asset files; consumers
 * delegate here instead of copying the walk-up logic.
 */
public final class RepoRootLocator {

    private RepoRootLocator() {
    }

    public static Path locate() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("core")) && Files.isDirectory(dir.resolve("ai-service"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate repo root from " + Path.of("").toAbsolutePath());
    }
}
