package com.sixbean.testpike.core.perf.api.controller;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.executor.domain.component.ProjectStorageLookup;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Perf artifact download-grant door : the perf-artifact
 * scene guard must refuse to sign foreign keys while canonical bundle keys
 * pass through to the presign single source.
 *
 * <p>Key shape mirrors StorageKeys.build: {projectId}/perf-artifact/{yyyyMM}/
 * {uuid8}-{name}.zip. Guard reuses ProjectStorageLookup.parsePerfArtifactProjectId
 * (same anchor the write route used — no second copy).
 */
class PerfArtifactDownloadGrantTest {

    private final ArtifactStorage artifactStorage = Mockito.mock(ArtifactStorage.class);
    private final ProjectStorageLookup lookup = Mockito.mock(ProjectStorageLookup.class);
    private final PerfArtifactInternalController controller =
            new PerfArtifactInternalController(artifactStorage, lookup);

    @Test
    void signsCanonicalPerfArtifactKeyThroughPresignSingleSource() {
        String key = "42/perf-artifact/202610/a1b2c3d4-Smoke.zip";
        Mockito.when(lookup.parsePerfArtifactProjectId(key))
                .thenReturn(java.util.OptionalLong.of(42L));
        Mockito.when(artifactStorage.grantViewUrl(key)).thenReturn("http://s3.local/presigned");

        Map<String, Object> data = controller.grantDownload(Map.of("objectKey", key)).getData();

        assertEquals(key, data.get("objectKey"));
        assertEquals("http://s3.local/presigned", data.get("downloadUrl"));
    }

    @Test
    void rejectsKeyOutsidePerfArtifactSceneWithoutSigning() {
        String foreign = "42/exec-artifact/202610/a1b2c3d4-shot.png";
        Mockito.when(lookup.parsePerfArtifactProjectId(foreign))
                .thenReturn(java.util.OptionalLong.empty());

        ServiceException refused = assertThrows(ServiceException.class,
                () -> controller.grantDownload(Map.of("objectKey", foreign)));
        assertTrue(refused.getMessage().contains("拒发下载授权"), refused.getMessage());
        Mockito.verifyNoInteractions(artifactStorage);
    }

    @Test
    void rejectsBlankKeyFailClosed() {
        assertThrows(ServiceException.class,
                () -> controller.grantDownload(Map.of("objectKey", "  ")));
        assertThrows(ServiceException.class,
                () -> controller.grantDownload(Map.of()));
        Mockito.verifyNoInteractions(artifactStorage);
    }

    @Test
    void guardAnchorAcceptsRealBuiltKeyEndToEndOfParser() {
        // Parser is pure (no DB), so run the REAL lookup instead of the mock:
        // a genuinely built perf-artifact key must parse, a same-shape foreign
        // scene key must not. This pins the guard against scene-string drift.
        ProjectStorageLookup real = new ProjectStorageLookup(null);
        String built = com.sixbean.testpike.common.storage.StorageKeys
                .build(null, 7L, "perf-artifact", "Sim.zip");
        assertNotNull(real.parsePerfArtifactProjectId(built).orElseThrow());
        String foreignScene = com.sixbean.testpike.common.storage.StorageKeys
                .build(null, 7L, "perf-dataset", "Sim.csv");
        assertTrue(real.parsePerfArtifactProjectId(foreignScene).isEmpty());
    }
}
