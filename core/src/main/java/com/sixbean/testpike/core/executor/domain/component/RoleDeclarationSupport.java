package com.sixbean.testpike.core.executor.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class RoleDeclarationSupport {

    private final ObjectMapper objectMapper;

    public RoleDeclarationSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    
    public List<RoleDeclaration> roles(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode roles = objectMapper.readTree(actionJson).path("roles");
            if (!roles.isArray()) {
                return List.of();
            }
            List<RoleDeclaration> declarations = new ArrayList<>();
            roles.forEach(role -> {
                String name = textOrNull(role.path("name"));
                String binding = textOrNull(role.path("binding"));
                if (name != null && binding != null) {
                    declarations.add(new RoleDeclaration(name, binding, textOrNull(role.path("credential"))));
                }
            });
            return List.copyOf(declarations);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank() ? null : node.asText();
    }
}
