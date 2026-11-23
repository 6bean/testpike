package com.sixbean.testpike.core.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Run-scoped namespace for tp_resource_lease suites .
 * Every resource_ref a suite creates sits under this run's prefix so teardown deletes only
 * rows owned by the run. Blanket TRUNCATE of the shared table is banned: it wipes leases held
 * by concurrent suites, other chains and L3 probes on the dev database.
 *
 * <p>Instances are produced and cleaned by {@link LeaseNamespaceExtension}; suites only declare
 * a {@code LeaseRefNamespace} parameter on the test methods that create refs.
 */
public final class LeaseRefNamespace {

    private final JdbcTemplate jdbcTemplate;
    private final String prefix;

    LeaseRefNamespace(JdbcTemplate jdbcTemplate, String prefix) {
        this.jdbcTemplate = jdbcTemplate;
        this.prefix = prefix;
    }

    /** Namespace bound to a stable key (one per test class), built by the extension. */
    public static LeaseRefNamespace of(JdbcTemplate jdbcTemplate, String key) {
        String slug = key.substring(key.lastIndexOf('.') + 1);
        String prefix = "lease-" + slug + "-" + Integer.toHexString(key.hashCode()) + "/";
        return new LeaseRefNamespace(jdbcTemplate, prefix);
    }

    /** Namespaced resource_ref for a suite-local base name. */
    public String ref(String base) {
        return prefix + base;
    }

    /** Namespace prefix, for filtering scan results down to this run's rows. */
    public String prefix() {
        return prefix;
    }

    /** Targeted teardown: removes every lease row created under this namespace (any state). */
    public void cleanOwnSlice() {
        jdbcTemplate.update("DELETE FROM tp_resource_lease WHERE resource_ref LIKE ?", prefix + "%");
    }
}
