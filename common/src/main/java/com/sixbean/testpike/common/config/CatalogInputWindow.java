package com.sixbean.testpike.common.config;

/**
 * Cross-module constant for the model catalog's conservative input window.
 *
 * <p>Why it lives here: two consumers must agree on the same number by
 * construction — the ai-service document-extraction budget planner falls
 * back to this window when a catalog row carries no usable
 * {@code prompt_limit_tokens}, and the core factory seeder writes this
 * window onto install-default rows so a fresh installation starts
 * calibrated instead of tripping the startup consistency probe with the
 * blanket UI default (1048576), which would inflate the extraction budget
 * beyond any in-catalog endpoint. A module-private copy of the value would
 * be exactly the duplicated source this constant removes.
 */
public final class CatalogInputWindow {

    /**
     * Token floor every in-catalog text generation model guarantees —
     * the conservative window assumed when a row is uncalibrated and the
     * declared window written at factory seeding time.
     */
    public static final int FALLBACK_INPUT_WINDOW_TOKENS = 128000;

    private CatalogInputWindow() {
    }
}
