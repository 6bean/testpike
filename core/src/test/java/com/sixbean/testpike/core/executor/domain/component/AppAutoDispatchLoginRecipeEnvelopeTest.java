package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEnvProfileService;
import com.sixbean.testpike.core.asset.api.dto.AssetPublishRequest;
import com.sixbean.testpike.core.asset.api.dto.SharedAssetInfo;
import com.sixbean.testpike.core.asset.domain.service.SharedAssetService;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dispatch-envelope injection of the bound login recipe (W5 phase 2, segment B1b).
 * <p>Verified behavior: a profile bound to a shared login_recipe asset prepends the
 * CURRENT head version's {@code app.*} steps before the case steps, the combined list
 * still passes the single credential-injection point (a vault reference inside the
 * recipe resolves to the vaulted secret, an unresolved one is refused — nothing is
 * shipped to the executor with a live placeholder), a re-published recipe is replayed
 * without touching the profile, and a withdrawn recipe fails closed at dispatch.
 * Binding is also gated at save: wrong kind / missing id / non-app variant are refused
 * with an honest message. Unbound profiles keep the historical envelope shape.
 * <p>Real dev-DB chain (SpringBootTest, no mocks): fixture projects/rows are created
 * through the publish and profile services and deleted by id afterwards (no TRUNCATE).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class AppAutoDispatchLoginRecipeEnvelopeTest {

    @Autowired
    private AppAutoDispatchSupport appAutoDispatchSupport;

    @Autowired
    private AppautoEnvProfileService envProfileService;

    @Autowired
    private SharedAssetService sharedAssetService;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private final List<Long> assetIds = new ArrayList<>();
    private final List<Long> profileIds = new ArrayList<>();
    private final List<Long> credentialIds = new ArrayList<>();
    private String recipeName;

    @BeforeEach
    void setUp() {
        String code = "lre" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "配方注入测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        recipeName = "商城登录配方-" + System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        for (Long id : profileIds) {
            jdbcTemplate.update("DELETE FROM tp_appauto_env_profile WHERE id = ?", id);
        }
        for (Long id : assetIds) {
            jdbcTemplate.update("DELETE FROM tp_asset WHERE id = ?", id);
        }
        for (Long id : credentialIds) {
            jdbcTemplate.update("DELETE FROM tp_credential WHERE id = ?", id);
        }
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    /** Publish one recipe version through the real publish chain (contract gate included). */
    private SharedAssetInfo publishRecipe(String kind, String payload) {
        SharedAssetInfo published = sharedAssetService.publish(1L, projectId,
                new AssetPublishRequest(projectId, kind, recipeName, payload));
        assetIds.add(published.id());
        return published;
    }

    /** App-variant payload: launch + fill user + fill password via a vault reference. */
    private String appRecipePayload(String marker) {
        return "{\"target\":\"app\",\"steps\":["
                + "{\"action\":\"app.launch\",\"params\":{\"pkg\":\"com.mall\",\"marker\":\"" + marker + "\"}},"
                + "{\"action\":\"app.input\",\"params\":{\"value\":\"qa_user\"}},"
                + "{\"action\":\"app.input\",\"params\":{\"value\":\"{{CRED:mall_password_b1b}}\"}}"
                + "]}";
    }

    private long bindProfile(long recipeAssetId) {
        long id = envProfileService.create(projectId, new AppautoEnvProfileInput(
                "绑定配方档案-" + System.nanoTime(), "ANDROID_SDK",
                Map.of("sdkHome", "/opt/android-sdk"), "B1b 夹具", recipeAssetId));
        profileIds.add(id);
        return id;
    }

    private List<Map<String, Object>> caseSteps() {
        return List.of(Map.of("action", "app.tap", "target", Map.of("pkg", "com.mall")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stepsOf(Map<String, Object> envelope) {
        return (List<Map<String, Object>>) envelope.get("steps");
    }

    @Test
    void bound_profile_prepends_recipe_steps_and_injects_vault_secret() {
        credentialIds.add(credentialService.create(projectId, new CredentialInput(
                "商城登录口令", "mall_password_b1b", "Sup3r-Secret-x", "B1b 夹具")));
        SharedAssetInfo recipe = publishRecipe("login_recipe", appRecipePayload("v1"));
        long profileId = bindProfile(recipe.id());

        Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                projectId, "MOBILEWRIGHT", profileId, caseSteps());

        List<Map<String, Object>> steps = stepsOf(envelope);
        assertEquals(4, steps.size(), "recipe head steps are prepended before the case step");
        assertEquals("app.launch", steps.get(0).get("action"),
                "the login recipe replay runs first");
        assertEquals("Sup3r-Secret-x",
                ((Map<String, Object>) steps.get(2).get("params")).get("value"),
                "a vault reference inside the recipe resolves at the same injection point");
        assertEquals("app.tap", steps.get(3).get("action"), "the case's own steps stay last");
        @SuppressWarnings("unchecked")
        Map<String, Object> profileView = (Map<String, Object>) envelope.get("envProfile");
        assertEquals(recipe.id(), ((Number) profileView.get("loginRecipeAssetId")).longValue(),
                "the envelope projection names the bound recipe (attribution anchor)");
    }

    @Test
    void re_published_recipe_head_is_replayed_without_touching_the_profile() {
        credentialIds.add(credentialService.create(projectId, new CredentialInput(
                "商城登录口令", "mall_password_b1b", "Sup3r-Secret-x", "B1b 夹具")));
        SharedAssetInfo v1 = publishRecipe("login_recipe", appRecipePayload("v1"));
        long profileId = bindProfile(v1.id());
        SharedAssetInfo v2 = publishRecipe("login_recipe", appRecipePayload("v2"));

        Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                projectId, "MOBILEWRIGHT", profileId, caseSteps());

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) stepsOf(envelope).get(0).get("params");
        assertEquals("v2", params.get("marker"),
                "dispatch resolves the CURRENT head of the chain, the bound row id stays unchanged");
        assertEquals(v2.id(), v2.id(), "re-publish appended a version row");
    }

    @Test
    void binding_a_wrong_kind_a_missing_id_or_a_non_app_recipe_is_refused_at_save() {
        SharedAssetInfo fragment = publishRecipe("case_fragment",
                "{\"steps\":[{\"action\":\"app.tap\"}]}");
        ServiceException wrongKind = assertThrows(ServiceException.class,
                () -> bindProfile(fragment.id()));
        assertTrue(wrongKind.getMessage().contains("login_recipe"),
                "the refusal names the kind the reference actually points to");

        assertThrows(ServiceException.class, () -> bindProfile(999_999_999L));

        SharedAssetInfo webRecipe = publishRecipe("login_recipe",
                "{\"target\":\"web\",\"steps\":[{\"action\":\"app.tap\"}]}");
        ServiceException webVariant = assertThrows(ServiceException.class,
                () -> bindProfile(webRecipe.id()));
        assertTrue(webVariant.getMessage().contains("APP"),
                "a web-variant recipe is refused with a pointer to the APP variant requirement");
    }

    @Test
    void withdrawn_recipe_fails_closed_at_dispatch() {
        SharedAssetInfo recipe = publishRecipe("login_recipe", appRecipePayload("v1"));
        long profileId = bindProfile(recipe.id());
        sharedAssetService.withdraw(1L, projectId, recipe.id(), true);

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> appAutoDispatchSupport.buildEnvelope(
                        projectId, "MOBILEWRIGHT", profileId, caseSteps()));
        assertTrue(refusal.getMessage().contains("已下架"),
                "a withdrawn recipe chain is rejected before any step reaches the executor");
    }

    @Test
    void unresolved_vault_reference_in_a_bound_recipe_is_refused_at_dispatch() {
        SharedAssetInfo recipe = publishRecipe("login_recipe",
                "{\"target\":\"app\",\"steps\":[{\"action\":\"app.launch\","
                        + "\"params\":{\"value\":\"{{CRED:never_vaulted_key}}\"}}]}");
        long profileId = bindProfile(recipe.id());

        assertThrows(ServiceException.class,
                () -> appAutoDispatchSupport.buildEnvelope(
                        projectId, "MOBILEWRIGHT", profileId, caseSteps()),
                "no envelope ships to the executor carrying an unresolved credential placeholder");
    }

    @Test
    void unbound_profile_keeps_the_historical_envelope_shape() {
        long profileId = envProfileService.create(projectId, new AppautoEnvProfileInput(
                "无绑定档案-" + System.nanoTime(), "ANDROID_SDK",
                Map.of("sdkHome", "/opt/android-sdk"), "历史形态回归钉"));
        profileIds.add(profileId);

        Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                projectId, "MOBILEWRIGHT", profileId, caseSteps());

        assertEquals(caseSteps(), stepsOf(envelope),
                "without a binding nothing is prepended — legacy envelopes keep their shape");
        @SuppressWarnings("unchecked")
        Map<String, Object> profileView = (Map<String, Object>) envelope.get("envProfile");
        assertTrue(!profileView.containsKey("loginRecipeAssetId"),
                "the attribution key only appears when a recipe is actually bound");
    }
}
