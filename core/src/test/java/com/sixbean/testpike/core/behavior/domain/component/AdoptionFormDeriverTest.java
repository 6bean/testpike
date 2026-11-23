package com.sixbean.testpike.core.behavior.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adoption form derivation : the four observed reactions on an
 * AI-produced case after human handling — straight adoption (adopted), light
 * edit (partial), heavy rewrite (corrected), deletion (deprecated) — are
 * derived purely statistically from the before/after text pair. Nothing here
 * blocks an edit; the derivation only classifies it. The soft-edit threshold
 * is a hot-reloaded config key re-read on every call, and out-of-range or
 * unparsable values fall back to the shipped default so a typo on the
 * settings page can never silence (or skew) the ledger.
 */
class AdoptionFormDeriverTest {

    private static final double DEFAULT = 0.2;

    @Test
    void straightAdoptionIsAdopted() {
        assertEquals("adopted", AdoptionFormDeriver.formFor(1.0, DEFAULT));
    }

    @Test
    void lightEditAtThresholdBoundaryIsPartial() {
        // Boundary sample: edit ratio exactly on the soft-edit threshold still
        // counts as a light edit (<= threshold), one hair above flips to heavy.
        assertEquals("partial", AdoptionFormDeriver.formFor(0.8, DEFAULT));
        assertEquals("corrected", AdoptionFormDeriver.formFor(0.7999, DEFAULT));
    }

    @Test
    void heavyRewriteAboveUsefulFloorIsDeprecated() {
        assertEquals("corrected", AdoptionFormDeriver.formFor(0.2, DEFAULT));
        assertEquals("deprecated", AdoptionFormDeriver.formFor(0.19, DEFAULT));
        assertEquals("deprecated", AdoptionFormDeriver.formFor(0.0, DEFAULT));
    }

    @Test
    void nonAiMaterialWithoutSnapshotIsNotCounted() {
        ConfigService config = Mockito.mock(ConfigService.class);
        AdoptionFormDeriver deriver = new AdoptionFormDeriver(config);
        assertNull(deriver.derive(null, "edited body"),
                "material with no AI snapshot is not an adoption observation");
        assertNull(deriver.derive("  ", "edited body"));
        verify(config, times(0)).getValue(anyString(), anyString());
    }

    @Test
    void deletionOfAiOutputDerivesDeprecatedWithoutReadingThreshold() {
        ConfigService config = Mockito.mock(ConfigService.class);
        AdoptionFormDeriver deriver = new AdoptionFormDeriver(config);
        AdoptionFormDeriver.AdoptionForm form = deriver.derive("AI 生成的步骤文本", "   ");
        assertEquals("deprecated", form.outcome());
        assertEquals(0.0, form.similarity(), 1e-9);
        verify(config, times(0)).getValue(anyString(), anyString());
    }

    @Test
    void similarityUsesRealEditDistanceOnTextPair() {
        ConfigService config = Mockito.mock(ConfigService.class);
        when(config.getValue(eq(AdoptionFormDeriver.KEY_SOFTEDIT_RATIO), anyString()))
                .thenReturn(String.valueOf(DEFAULT));
        AdoptionFormDeriver deriver = new AdoptionFormDeriver(config);
        // One character removed from a 10-char text: distance 1 / length 10 = 0.1 ratio.
        AdoptionFormDeriver.AdoptionForm form = deriver.derive("0123456789", "012345678");
        assertEquals(0.9, form.similarity(), 1e-9);
        assertEquals("partial", form.outcome());
    }

    @Test
    void thresholdIsReReadEveryCallAndBadValuesFallBackToDefault() {
        ConfigService config = Mockito.mock(ConfigService.class);
        when(config.getValue(eq(AdoptionFormDeriver.KEY_SOFTEDIT_RATIO), anyString()))
                .thenReturn("5", "abc");
        AdoptionFormDeriver deriver = new AdoptionFormDeriver(config);
        // With the shipped default back in force, edit ratio 0.8 is a heavy
        // rewrite still recognizably derived (corrected), not a rejection.
        AdoptionFormDeriver.AdoptionForm first = deriver.derive("abcdefghij", "ab");
        assertEquals("corrected", first.outcome());
        AdoptionFormDeriver.AdoptionForm second = deriver.derive("0123456789", "01234567");
        assertEquals("partial", second.outcome());
        verify(config, times(2)).getValue(eq(AdoptionFormDeriver.KEY_SOFTEDIT_RATIO), anyString());
    }
}
