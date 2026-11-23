package com.sixbean.testpike.core.program;

import com.sixbean.testpike.core.program.domain.service.ProgramMemberService;
import com.sixbean.testpike.core.program.domain.service.ProgramService;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Supplies the shared ProgramLensFixture to lens test methods and tears fixture rows
 * down after each case — wiring lives once here instead of being repeated per class.
 *
 * <p>Usage: {@code @ExtendWith(ProgramLensFixtureExtension.class)} on the test class,
 * then declare a {@code ProgramLensFixture} parameter on the test methods that need it.
 */
public final class ProgramLensFixtureExtension implements ParameterResolver, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(ProgramLensFixtureExtension.class);

    private static final String KEY = "fixture";

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
                                     ExtensionContext extensionContext) {
        return ProgramLensFixture.class.equals(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext,
                                   ExtensionContext extensionContext) {
        ExtensionContext store = extensionContext.getRoot();
        ProgramLensFixture existing = store.getStore(NAMESPACE).get(KEY, ProgramLensFixture.class);
        if (existing != null) {
            return existing;
        }
        ApplicationContext ctx = SpringExtension.getApplicationContext(extensionContext);
        ProgramLensFixture fixture = new ProgramLensFixture(
                ctx.getBean(ProjectService.class),
                ctx.getBean(MemberService.class),
                ctx.getBean(ProgramService.class),
                ctx.getBean(ProgramMemberService.class),
                ctx.getBean(JdbcTemplate.class));
        store.getStore(NAMESPACE).put(KEY, fixture);
        return fixture;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ProgramLensFixture fixture = context.getRoot().getStore(NAMESPACE).remove(KEY,
                ProgramLensFixture.class);
        if (fixture != null) {
            fixture.cleanup();
        }
    }
}
