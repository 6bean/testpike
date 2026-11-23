package com.sixbean.testpike.core.tool.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for the built-in login-state register tool seed.
 *
 * <p>Guards the hand-written JSON schema constant: the provider rejects the
 * whole tool registration when the input schema does not parse, which silently
 * leaves the chat journey without the register write tool (observed as the
 * model falling back to chat.clarify with search_tools round after round).
 * Every property value object must be closed and every field name quoted, so
 * this test parses the constant and walks the expected shape.
 */
class LoginStateRegisterToolSeedSchemaTest {

    @Test
    void registerSchemaParsesAsClosedJson() throws Exception {
        JsonNode root = new ObjectMapper()
                .readTree(LoginStateRegisterToolSeedInitializer.SCHEMA_LOGIN_STATE_REGISTER);

        assertEquals("object", root.path("type").asText(), "root schema type");
        assertTrue(root.path("x-write").asBoolean(), "root schema must stay marked as write");

        JsonNode props = root.path("properties");
        assertTrue(props.isObject(), "properties must be an object");
        for (String key : new String[] {"stagingToken", "stateName", "entries"}) {
            assertTrue(props.path(key).isObject(), "property '" + key + "' must be a closed object");
        }
        assertEquals("string", props.path("stagingToken").path("type").asText());
        assertEquals("string", props.path("stateName").path("type").asText());
        assertEquals("array", props.path("entries").path("type").asText());
        assertEquals("object", props.path("entries").path("items").path("type").asText());
        assertEquals("string", props.path("entries").path("items").path("properties")
                .path("name").path("type").asText());
        assertEquals("boolean", props.path("entries").path("items").path("properties")
                .path("selected").path("type").asText());

        JsonNode itemsRequired = props.path("entries").path("items").path("required");
        assertEquals(1, itemsRequired.size(), "items must require only the entry name");
        assertEquals("name", itemsRequired.get(0).asText());

        JsonNode rootRequired = root.path("required");
        assertEquals(3, rootRequired.size(), "root required must list the three arguments");
        assertEquals("stagingToken", rootRequired.get(0).asText());
        assertEquals("stateName", rootRequired.get(1).asText());
        assertEquals("entries", rootRequired.get(2).asText());
    }

    /**
     * Contract guidance assertions from the leg-1 probe verdict: the model
     * found the register contract through search_tools but produced a clarify
     * question instead of a draft card, because the schema never told it what
     * to do when the user omits the state name or the tick selection. Both
     * are carried by the draft card itself, so the schema must explicitly
     * instruct the model to decide by itself and call the tool in the same
     * round (naming guidance) and to default to selecting every parsed row
     * (tick guidance).
     */
    @Test
    void registerSchemaCarriesSelfNamingAndSelectAllGuidance() throws Exception {
        JsonNode props = new ObjectMapper()
                .readTree(LoginStateRegisterToolSeedInitializer.SCHEMA_LOGIN_STATE_REGISTER)
                .path("properties");

        String stateName = props.path("stateName").path("description").asText();
        assertTrue(stateName.contains("自行拟定"),
                "stateName description must tell the model to compose a name itself");
        assertTrue(stateName.contains("不要向用户追问"),
                "stateName description must forbid clarifying the name back to the user");

        String entries = props.path("entries").path("description").asText();
        assertTrue(entries.contains("未指明") && entries.contains("全选"),
                "entries description must default to selecting all parsed rows when unspecified");
    }
}
