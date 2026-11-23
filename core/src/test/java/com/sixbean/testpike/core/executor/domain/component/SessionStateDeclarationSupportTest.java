package com.sixbean.testpike.core.executor.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser for the top-level sessionState selector of a browser action JSON
 * . The claim assembly materialises
 * the login state through this single read point.
 * <p>Fail-safe discipline shared with RoleDeclarationSupport: absent segment,
 * dirty JSON, non-object value or blank selectors all mean "no declaration"
 * (null) so legacy action JSON keeps the old claim shape and never blows up
 * the claim round. The strict structural gate lives in the common
 * ActionJsonValidator save chain — this component never copies it (red line 6).
 */
class SessionStateDeclarationSupportTest {

    private final SessionStateDeclarationSupport support =
            new SessionStateDeclarationSupport(new ObjectMapper());

    @Test
    void nameSelectorIsParsedVerbatim() {
        SessionStateDeclaration decl = support.declaration(
                "{\"steps\":[],\"sessionState\":{\"name\":\"买家已登录\"}}");
        assertNotNull(decl, "a declared selector must parse");
        assertEquals("买家已登录", decl.name());
        assertNull(decl.roleScope());
    }

    @Test
    void roleScopeSelectorIsParsedVerbatim() {
        SessionStateDeclaration decl = support.declaration(
                "{\"sessionState\":{\"roleScope\":\"审批人\"}}");
        assertNotNull(decl);
        assertNull(decl.name());
        assertEquals("审批人", decl.roleScope());
    }

    @Test
    void absentOrDirtyMeansNoDeclarationWithoutThrowing() {
        assertNull(support.declaration(null));
        assertNull(support.declaration(""));
        assertNull(support.declaration("{\"steps\":[]}"), "absent segment = zero drift");
        assertNull(support.declaration("{not json"), "dirty JSON must never break the claim round");
        assertNull(support.declaration("{\"sessionState\":\"买家\"}"), "non-object = no declaration");
        assertNull(support.declaration("{\"sessionState\":{\"name\":\"  \"}}"),
                "blank selectors = no valid declaration (the save gate refuses the shape)");
    }

    @Test
    void blankSelectorFieldDropsToNull() {
        SessionStateDeclaration decl = support.declaration(
                "{\"sessionState\":{\"name\":\"sbs-a\",\"roleScope\":\"\"}}");
        assertNotNull(decl);
        assertEquals("sbs-a", decl.name());
        assertNull(decl.roleScope(), "a blank field reads as undeclared (textOrNull discipline)");
        assertTrue(decl.hasName());
    }
}
