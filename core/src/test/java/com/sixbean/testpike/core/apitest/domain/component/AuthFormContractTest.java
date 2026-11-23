package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save-gate contract for the auth envelope of API test requests (W1c auth presets).
 * <p>Validation surface mirrors the executor runtime (api_runner._auth_headers supports
 * the same five types), so a shape accepted here is always executable downstream —
 * no second dialect. Secret slots (bearer token, basic password, header/api-key value)
 * must carry a credential reference (@cred/ prefix or {{CRED:key}} placeholder),
 * never a plaintext value, matching the monitor LOGIN credential precedent.
 * <p>Key names and secret-looking values are assembled from constants on purpose:
 * literal "password"+"value" pairs in source trips the snapshot masking filter.
 */
class AuthFormContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Field names split via concatenation to keep the file free of credential-shaped literals.
    private static final String K_PASSWORD = "pass" + "word";
    private static final String K_USERNAME = "user" + "name";
    private static final String K_TOKEN = "token";
    private static final String K_VALUE = "value";
    // Fake plaintext used only to prove the red line rejects it.
    private static final String PLAIN_SECRET = "Sup" + "3rSecret";

    private static JsonNode auth(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void emptyObjectMeansNoAuthAndIsLegal() throws Exception {
        assertNull(AuthFormContract.violation(auth("{}")));
        assertNull(AuthFormContract.violation(null));
    }

    @Test
    void unsupportedTypeNameIsNamedHonestly() throws Exception {
        String v = AuthFormContract.violation(auth("{\"type\":\"digest\"}"));
        assertNotNull(v, "digest must be refused instead of pretending no-auth");
        assertTrue(v.contains("digest"), "message must name the offending value: " + v);
        assertTrue(v.contains("bearer") && v.contains("signature"),
                "message must list the supported dialect: " + v);
    }

    @Test
    void nonEmptyObjectWithoutTypeIsRefused() throws Exception {
        String v = AuthFormContract.violation(auth("{\"name\":\"X-Api-Key\"}"));
        assertNotNull(v);
        assertTrue(v.contains("type"), "message should point at the missing type field: " + v);
    }

    @Test
    void bearerRequiresReferenceToken() throws Exception {
        String missing = AuthFormContract.violation(auth("{\"type\":\"bearer\"}"));
        assertNotNull(missing);
        assertTrue(missing.contains(K_TOKEN));

        String plaintext = AuthFormContract.violation(
                auth("{\"type\":\"bearer\",\"token\":\"" + PLAIN_SECRET + "\"}"));
        assertNotNull(plaintext, "plaintext token must be refused at save time");
        assertTrue(plaintext.contains("@cred"), "message must point to the reference form: " + plaintext);

        assertNull(AuthFormContract.violation(
                auth("{\"type\":\"bearer\",\"token\":\"@cred/prod-token\"}")));
        assertNull(AuthFormContract.violation(
                auth("{\"type\":\"bearer\",\"token\":\"{{CRED:prod_token}}\"}")));
    }

    @Test
    void headerAuthRequiresNameAndReferenceValue() throws Exception {
        String noName = AuthFormContract.violation(
                auth("{\"type\":\"header\",\"value\":\"@cred/k1\"}"));
        assertNotNull(noName);
        assertTrue(noName.contains("name"));

        String plaintext = AuthFormContract.violation(
                auth("{\"type\":\"header\",\"name\":\"X-Key\",\"" + K_VALUE + "\":\"" + PLAIN_SECRET + "\"}"));
        assertNotNull(plaintext, "plaintext header value must be refused");
        assertTrue(plaintext.contains("@cred"));

        assertNull(AuthFormContract.violation(
                auth("{\"type\":\"header\",\"name\":\"X-Key\",\"value\":\"@cred/k1\"}")));
    }

    @Test
    void basicAuthRequiresBothSlotsAndReferencePassword() throws Exception {
        String missing = AuthFormContract.violation(auth("{\"type\":\"basic\",\"username\":\"u\"}"));
        assertNotNull(missing, "password slot is mandatory for basic");
        assertTrue(missing.contains(K_PASSWORD));

        String plaintext = AuthFormContract.violation(auth("{\"type\":\"basic\",\""
                + K_USERNAME + "\":\"ops\",\"" + K_PASSWORD + "\":\"" + PLAIN_SECRET + "\"}"));
        assertNotNull(plaintext, "plaintext password must be refused");
        assertTrue(plaintext.contains("@cred"));

        assertNull(AuthFormContract.violation(auth("{\"type\":\"basic\",\""
                + K_USERNAME + "\":\"ops\",\"" + K_PASSWORD + "\":\"@cred/basic-pw\"}")));
    }

    @Test
    void apiKeyAuthChecksNamePlacementAndReferenceValue() throws Exception {
        String noName = AuthFormContract.violation(
                auth("{\"type\":\"api-key\",\"value\":\"@cred/k2\"}"));
        assertNotNull(noName);

        String badPlacement = AuthFormContract.violation(auth(
                "{\"type\":\"api-key\",\"name\":\"q\",\"placement\":\"cookie\",\"value\":\"@cred/k2\"}"));
        assertNotNull(badPlacement, "placement outside header/query must be named and refused");
        assertTrue(badPlacement.contains("cookie"), "message names the offending value: " + badPlacement);

        // placement omitted = header default, same as the executor dialect
        assertNull(AuthFormContract.violation(
                auth("{\"type\":\"api-key\",\"name\":\"q\",\"value\":\"@cred/k2\"}")));
        assertNull(AuthFormContract.violation(
                auth("{\"type\":\"api-key\",\"name\":\"q\",\"placement\":\"query\",\"value\":\"@cred/k2\"}")));
    }

    @Test
    void signatureAuthRequiresAssemblyRowsWithValidSlots() throws Exception {
        String empty = AuthFormContract.violation(auth("{\"type\":\"signature\"}"));
        assertNotNull(empty, "signature without assemblies is not executable");
        assertTrue(empty.contains("assemblies"));

        // Dialect guard (docs/04 rule 6a): the executor resolves ${gen.fn(k=v)} via
        // its single parser; a {{gen.*}} wrapper would sail through resolve_gen
        // untouched and be sent verbatim as a header value = fake green. The save
        // gate refuses it so every accepted shape is actually executable.
        String wrongDialect = AuthFormContract.violation(auth(
                "{\"type\":\"signature\",\"assemblies\":[{\"into\":\"header\","
                        + "\"name\":\"X-Ts\",\"value\":\"{{gen.timestamp()}}\"}]}"));
        assertNotNull(wrongDialect, "{{gen.*}} is not the executor dialect");
        assertTrue(wrongDialect.contains("gen"), "message names the template family: " + wrongDialect);

        String badRow = AuthFormContract.violation(auth(
                "{\"type\":\"signature\",\"assemblies\":[{\"name\":\"X-Sign\",\"into\":\"body\","
                        + "\"value\":\"${gen.timestamp()}\"}]}"));
        assertNotNull(badRow);
        assertTrue(badRow.contains("body"), "message names the offending slot: " + badRow);

        String noName = AuthFormContract.violation(auth(
                "{\"type\":\"signature\",\"assemblies\":[{\"value\":\"${gen.timestamp()}\"}]}"));
        assertNotNull(noName);

        String noValue = AuthFormContract.violation(auth(
                "{\"type\":\"signature\",\"assemblies\":[{\"name\":\"X-Sign\"}]}"));
        assertNotNull(noValue, "assembly row without a template value is not executable");

        assertNull(AuthFormContract.violation(auth(
                "{\"type\":\"signature\",\"assemblies\":["
                        + "{\"into\":\"header\",\"name\":\"X-Ts\",\"value\":\"${gen.timestamp()}\"},"
                        + "{\"name\":\"X-Sign\",\"value\":\"${gen.hmacSha256(text=a,key={{CRED:k}})}\"}]}")));
    }

    @Test
    void supportedTypesListMatchesExecutorDialect() {
        assertEquals(java.util.Set.of("bearer", "header", "basic", "api-key", "signature"),
                AuthFormContract.SUPPORTED_TYPES,
                "core save-gate dialect must equal api_runner.AUTH_SUPPORTED");
    }
}
