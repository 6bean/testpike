package com.sixbean.testpike.core.executor.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

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
@Component
public class SessionStateDeclarationSupport {

    private final ObjectMapper objectMapper;

    public SessionStateDeclarationSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Parsed selector declaration, or null when the case declares nothing. */
    public SessionStateDeclaration declaration(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            return null;
        }
        try {
            JsonNode segment = objectMapper.readTree(actionJson).path("sessionState");
            if (!segment.isObject()) {
                return null;
            }
            String name = textOrNull(segment.path("name"));
            String roleScope = textOrNull(segment.path("roleScope"));
            if (name == null && roleScope == null) {
                return null;
            }
            return new SessionStateDeclaration(name, roleScope);
        } catch (Exception malformed) {
            return null;   // dirty JSON = no declaration (never break the claim round)
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank() ? null : node.asText();
    }
}
