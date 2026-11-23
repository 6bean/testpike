package com.sixbean.testpike.core.apiaccess.domain.component;

/**
 * Coercion of a call argument that names a data row into its numeric id.
 *
 * <p>Path and query parameters arrive at the door as strings while programmatic calls pass
 * numbers. Every family bridge reads its row id through this one rule so the door's fail-closed
 * behaviour cannot drift between families: anything that is not a plain number resolves to null,
 * and the row check then refuses the call instead of guessing.
 */
public final class RowIdValues {

    private RowIdValues() {
    }

    /** Numeric id parsed from the raw argument value, null when it is absent or not a number. */
    public static Long longOrNull(Object rowIdValue) {
        if (rowIdValue instanceof Number number) {
            return number.longValue();
        }
        if (rowIdValue instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }
}
