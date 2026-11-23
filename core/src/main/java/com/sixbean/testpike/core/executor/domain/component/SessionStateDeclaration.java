package com.sixbean.testpike.core.executor.domain.component;

/**
 * One parsed sessionState declaration (04 contract AV extension): a browser
 * case names the login state either by its ledger name or by a test role.
 * Exactly one selector field is filled — {@code null} on the unused side.
 * Traceability anchor: the structural gate in the common ActionJsonValidator
 * keeps declarations honest at save time; this record only carries the read.
 */
public record SessionStateDeclaration(String name, String roleScope) {

    /** True when the selector is the precise ledger name. */
    public boolean hasName() {
        return name != null;
    }
}
