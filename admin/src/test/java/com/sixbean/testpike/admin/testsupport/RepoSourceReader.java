package com.sixbean.testpike.admin.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared reader for source-text contract tests: resolves a path against the
 * repository root (the directory holding db/migration) instead of each test
 * mirroring its own walk-up helper (single-source rule for test fixtures).
 */
public final class RepoSourceReader {

    private RepoSourceReader() {
    }

    /** Read a repository-relative source file; surefire cwd is the module dir. */
    public static String read(String repoRelativePath) throws Exception {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("db/migration"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repository root with db/migration not found from "
                    + System.getProperty("user.dir"));
        }
        return Files.readString(dir.resolve(repoRelativePath));
    }
}
