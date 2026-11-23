package com.sixbean.testpike.admin.prompt.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.prompt.api.dto.BehaviorOutcomeAdvisoryView;
import com.sixbean.testpike.admin.prompt.api.dto.BehaviorRolloutView;
import com.sixbean.testpike.admin.prompt.api.dto.PromptTemplateFamilyView;
import com.sixbean.testpike.admin.prompt.api.dto.PromptTemplateSaveRequest;
import com.sixbean.testpike.admin.prompt.api.dto.PromptTemplateView;
import com.sixbean.testpike.admin.prompt.infrastructure.remote.client.PromptTemplateCoreClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class PromptTemplateBridgeTest {

    private final PromptTemplateCoreClient coreClient = mock(PromptTemplateCoreClient.class);
    private final PromptTemplateController controller = new PromptTemplateController(coreClient);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    
    @Test
    void saveOverridesCreatedByWithLoginUser() {
        UserContext.set(new CurrentUser(7L, "qa", "QA", null, List.of(), 0L, null));
        when(coreClient.save(new PromptTemplateSaveRequest("stage1", "case-gen", "正文", 7L)))
                .thenReturn(template(1L, 1));

        Response<PromptTemplateView> resp = controller.save(
                new PromptTemplateSaveRequest("stage1", "case-gen", "正文", 999L));

        assertEquals("0", resp.getCode());
        assertEquals(1L, resp.getData().id());
        verify(coreClient).save(new PromptTemplateSaveRequest("stage1", "case-gen", "正文", 7L));
    }

    
    @Test
    void rollbackPassesSourceIdAndLoginOperator() {
        UserContext.set(new CurrentUser(7L, "qa", "QA", null, List.of(), 0L, null));
        when(coreClient.rollback(3L, 7L)).thenReturn(template(4L, 1));

        Response<PromptTemplateView> resp = controller.rollback(3L);

        assertEquals("0", resp.getCode());
        assertEquals(4L, resp.getData().id());
        verify(coreClient).rollback(eq(3L), eq(7L));
    }

    
    @Test
    void detailPassesIdThrough() {
        when(coreClient.detail(2L)).thenReturn(template(2L, 2));

        assertEquals(2, controller.detail(2L).getData().version());
        verify(coreClient).detail(eq(2L));
    }

    
    @Test
    void listVersionsPassesNameAndDomain() {
        when(coreClient.listVersions("stage1", "case-gen"))
                .thenReturn(List.of(template(2L, 2), template(1L, 3)));

        Response<List<PromptTemplateView>> resp = controller.listVersions("stage1", "case-gen");

        assertEquals(2, resp.getData().size());
        verify(coreClient).listVersions(eq("stage1"), eq("case-gen"));
    }

    
    @Test
    void latestPassesThroughNull() {
        when(coreClient.latest("none", "x")).thenReturn(null);

        assertEquals("0", controller.latest("none", "x").getCode());
        assertEquals(null, controller.latest("none", "x").getData());
    }

    
    @Test
    void latestByStatusPassesStatusThrough() {
        when(coreClient.latestByStatus("stage1", "case-gen", 1)).thenReturn(template(5L, 5));

        assertEquals(5L, controller.latestByStatus("stage1", "case-gen", 1).getData().id());
        verify(coreClient).latestByStatus(eq("stage1"), eq("case-gen"), eq(1));
    }

    
    @Test
    void promotePassesIdThrough() {
        when(coreClient.promote(5L)).thenReturn(template(5L, 2));

        assertEquals(5L, controller.promote(5L).getData().id());
        verify(coreClient).promote(eq(5L));
    }

    
    @Test
    void viewMirrorsAllCoreItemFields() {
        when(coreClient.detail(9L)).thenReturn(new PromptTemplateView(
                9L, "stage1", "case-gen", 3, "模板正文", 1, 7L, 2L, "2026-09-25 10:00:00"));

        PromptTemplateView row = controller.detail(9L).getData();

        assertEquals(9L, row.id());
        assertEquals("stage1", row.name());
        assertEquals("case-gen", row.domain());
        assertEquals(3, row.version());
        assertEquals("模板正文", row.content());
        assertEquals(1, row.status());
        assertEquals(7L, row.createdBy());
        assertEquals(2L, row.rollbackTo());
        assertEquals("2026-09-25 10:00:00", row.createdAt());
    }

    
    @Test
    void writeLegsCarryModelUpdatePermission() throws Exception {
        Method save = PromptTemplateController.class.getMethod("save",
                PromptTemplateSaveRequest.class);
        assertEquals("perm:feedback:model-update",
                save.getAnnotation(RequirePermission.class).value());
        Method rollback = PromptTemplateController.class.getMethod("rollback", Long.class);
        assertEquals("perm:feedback:model-update",
                rollback.getAnnotation(RequirePermission.class).value());
        Method promote = PromptTemplateController.class.getMethod("promote", Long.class);
        assertEquals("perm:feedback:model-update",
                promote.getAnnotation(RequirePermission.class).value());
    }

    
    @Test
    void readLegsCarrySameModelUpdatePermission() throws Exception {
        Method detail = PromptTemplateController.class.getMethod("detail", Long.class);
        assertEquals("perm:feedback:model-update",
                detail.getAnnotation(RequirePermission.class).value());
        Method versions = PromptTemplateController.class.getMethod("listVersions",
                String.class, String.class);
        assertEquals("perm:feedback:model-update",
                versions.getAnnotation(RequirePermission.class).value());
    }

    /** Families enumeration: domain filter passes through untouched, family row mirrors all seven fields. */
    @Test
    void listFamiliesPassesDomainFilterAndMirrorsFamilyRow() {
        when(coreClient.listFamilies("case-gen")).thenReturn(List.of(
                new PromptTemplateFamilyView("case-gen", "stage2", 11L, 3, 2, 7,
                        "2026-11-05 10:00:00")));

        Response<List<PromptTemplateFamilyView>> resp = controller.listFamilies("case-gen");

        assertEquals("0", resp.getCode());
        PromptTemplateFamilyView family = resp.getData().get(0);
        assertEquals("case-gen", family.domain());
        assertEquals("stage2", family.name());
        assertEquals(11L, family.latestId());
        assertEquals(3, family.latestVersion());
        assertEquals(2, family.latestStatus());
        assertEquals(7, family.versionCount());
        assertEquals("2026-11-05 10:00:00", family.updatedAt());
        verify(coreClient).listFamilies("case-gen");
    }

    /** All-family read entry: omitted domain reaches core as null (presence/absence rules live in core alone). */
    @Test
    void listFamiliesWithoutDomainMeansAllFamilies() {
        when(coreClient.listFamilies(null)).thenReturn(List.of());

        Response<List<PromptTemplateFamilyView>> resp = controller.listFamilies(null);

        assertEquals("0", resp.getCode());
        assertEquals(0, resp.getData().size());
        verify(coreClient).listFamilies(null);
    }

    /** Permission gate: the families read leg carries the same D4 key as every other leg. */
    @Test
    void familiesLegCarriesSameModelUpdatePermission() throws Exception {
        Method families = PromptTemplateController.class.getMethod("listFamilies", String.class);
        assertEquals("perm:feedback:model-update",
                families.getAnnotation(RequirePermission.class).value());
    }

    /** Rollout advisory leg: domain/name pass through untouched, the advisory fields
 * mirror core's verdict without the bridge re-judging anything . */
    @Test
    void rolloutAdvisoryPassesDomainAndNameAndMirrorsAdvisory() {
        BehaviorOutcomeAdvisoryView advisory = new BehaviorOutcomeAdvisoryView(
                "case-gen", 3L, 5L, new BigDecimal("0.82"), new BigDecimal("0.71"),
                new BigDecimal("11.0"), 10, true, "candidate trails control beyond tolerance");
        when(coreClient.rolloutAdvisory("case-gen", "stage1")).thenReturn(
                new BehaviorRolloutView("case-gen", "stage1", 5L, 3L, advisory));

        Response<BehaviorRolloutView> resp = controller.rolloutAdvisory("case-gen", "stage1");

        assertEquals("0", resp.getCode());
        BehaviorRolloutView view = resp.getData();
        assertEquals("case-gen", view.domain());
        assertEquals("stage1", view.name());
        assertEquals(5L, view.grayId());
        assertEquals(3L, view.controlId());
        assertEquals(advisory, view.advisory());
        assertEquals(true, view.advisory().rollbackAdvisory());
        verify(coreClient).rolloutAdvisory(eq("case-gen"), eq("stage1"));
    }

    /** No live gray release: core answers data=null and the bridge relays null
     *  instead of inventing a verdict (advisory-grade read, same null leg shape
     *  as the latest() precedent). */
    @Test
    void rolloutAdvisoryPassesThroughNullWhenNoGrayRelease() {
        when(coreClient.rolloutAdvisory("case-gen", "stage9")).thenReturn(null);

        Response<BehaviorRolloutView> resp = controller.rolloutAdvisory("case-gen", "stage9");

        assertEquals("0", resp.getCode());
        assertEquals(null, resp.getData());
    }

    /** Permission gate: the rollout advisory read leg carries the same D4 key as
     *  every other leg of this controller (no new key, K12 drift precedent). */
    @Test
    void rolloutAdvisoryLegCarriesSameModelUpdatePermission() throws Exception {
        Method leg = PromptTemplateController.class.getMethod("rolloutAdvisory",
                String.class, String.class);
        assertEquals("perm:feedback:model-update",
                leg.getAnnotation(RequirePermission.class).value());
    }

    private static PromptTemplateView template(long id, int version) {
        return new PromptTemplateView(id, "stage1", "case-gen", version, "正文",
                2, 7L, null, "2026-09-25 10:00:00");
    }
}
