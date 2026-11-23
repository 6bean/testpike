package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shape contract for the batch defaults door (GET /ai/batch/limit). One door,
 * two halves: the row cap and the default-enumeration switch. The page must
 * never have to guess when the switch half is absent, so the normalization
 * fallback (on) is pinned at every layer the value rides through — core read,
 * internal door echo, admin bridge passthrough, and the seed migration.
 * Static source assertions follow the bridge-contract precedent in this package
 * (reflection/read of the source of truth, no second implementation).
 */
class RestCoreAiBatchClientLimitContractTest {

    private static final String KEY = "ai.gen.batch.auto-enumerate.enabled";

    private static String read(String rel) throws Exception {
        // Surefire runs with the module dir as cwd; walk up until the repo root
        // (the one holding db/migration) is found, then resolve from there.
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("db/migration"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root with db/migration").isNotNull();
        return Files.readString(dir.resolve(rel));
    }

    @Test
    void coreReadsTheKeyWithAnOnFallbackAndTheDoorEchoesBothHalves() throws Exception {
        String impl = read("core/src/main/java/com/sixbean/testpike/core/ai/domain/service/impl/AiBatchServiceImpl.java");
        assertThat(impl).contains("KEY_BATCH_AUTO_ENUMERATE = \"" + KEY + "\"");
        assertThat(impl).containsPattern("(?s)currentAutoEnumerate\\(\\)[\\s\\S]*getBoolean\\(\\s*KEY_BATCH_AUTO_ENUMERATE\\s*,\\s*true");
        String door = read("core/src/main/java/com/sixbean/testpike/core/ai/api/controller/AiBatchInternalController.java");
        assertThat(door).contains("\"autoEnumerate\"");
        assertThat(door).contains("currentAutoEnumerate()");
    }

    @Test
    void adminBridgeNormalizesTheSwitchHalfToOnWhenTheCoreIsOlder() throws Exception {
        String client = read("admin/src/main/java/com/sixbean/testpike/admin/ai/infrastructure/remote/client/RestCoreAiBatchClient.java");
        assertThat(client).containsPattern("(?s)autoEnumerate[\\s\\S]*\\? 1 : body.get\\(\"autoEnumerate\"\\)");
        // The controller passes the whole map through — no second default table.
        String controller = read("admin/src/main/java/com/sixbean/testpike/admin/ai/api/controller/AiController.java");
        assertThat(controller).contains("coreAiBatchClient.batchLimit()");
    }

    @Test
    void seedMigrationCarriesTheSameKeyWithTheOnValue() throws Exception {
        String seed = read("db/migration/V613__gen_batch_auto_enumerate_seed.sql");
        assertThat(seed).contains("'" + KEY + "', 'true'");
        assertThat(seed).contains("ON CONFLICT (config_key) DO NOTHING");
    }
}
