package com.sixbean.testpike.core.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Supplies the shared {@link LeaseRefNamespace} to lease-family suites and runs the targeted
 * teardown before/after each case — the wiring lives once here instead of being repeated per
 * class (same pattern as ProgramLensFixtureExtension).
 *
 * <p>Usage: {@code @ExtendWith(LeaseNamespaceExtension.class)} on the test class, then declare a
 * {@code LeaseRefNamespace} parameter on the test methods that create refs. One namespace per test
 * class: @BeforeEach also clears rows left by the previous method of the same suite.
 */
public final class LeaseNamespaceExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(LeaseNamespaceExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
                                     ExtensionContext extensionContext) {
        return LeaseRefNamespace.class.equals(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext,
                                   ExtensionContext extensionContext) {
        return namespaceFor(extensionContext);
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) {
        LeaseRefNamespace.of(jdbcTemplate(extensionContext), namespaceKey(extensionContext)).cleanOwnSlice();
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        namespaceFor(extensionContext).cleanOwnSlice();
    }

    private static LeaseRefNamespace namespaceFor(ExtensionContext extensionContext) {
        String key = namespaceKey(extensionContext);
        ExtensionContext store = extensionContext.getRoot();
        LeaseRefNamespace existing = store.getStore(NAMESPACE).get(key, LeaseRefNamespace.class);
        if (existing != null) {
            return existing;
        }
        LeaseRefNamespace created = LeaseRefNamespace.of(jdbcTemplate(extensionContext), key);
        store.getStore(NAMESPACE).put(key, created);
        return created;
    }

    /** Class-scoped key so sequential methods of one suite share the namespace and its leftovers. */
    private static String namespaceKey(ExtensionContext extensionContext) {
        return "lease-ns:" + extensionContext.getRequiredTestClass().getName();
    }

    private static JdbcTemplate jdbcTemplate(ExtensionContext extensionContext) {
        ApplicationContext ctx = SpringExtension.getApplicationContext(extensionContext);
        return ctx.getBean(JdbcTemplate.class);
    }
}
