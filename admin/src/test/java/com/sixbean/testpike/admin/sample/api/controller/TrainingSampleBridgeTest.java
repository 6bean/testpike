package com.sixbean.testpike.admin.sample.api.controller;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.sample.api.dto.TrainingSampleView;
import com.sixbean.testpike.admin.sample.infrastructure.remote.client.TrainingSampleCoreClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class TrainingSampleBridgeTest {

    private final TrainingSampleCoreClient coreClient = mock(TrainingSampleCoreClient.class);
    private final TrainingSampleController controller = new TrainingSampleController(coreClient);

    
    @Test
    void listPassesFiltersThroughToCore() {
        when(coreClient.listSamples(eq(11L), eq("驳回"), eq(2), eq(10), eq("created_at"), eq("asc")))
                .thenReturn(new PageView<>(List.of(sample(1L, "驳回")), 11L));

        Response<PageView<TrainingSampleView>> resp =
                controller.list(11L, "驳回", 2, 10, "created_at", "asc");

        assertEquals("0", resp.getCode());
        assertEquals(11L, resp.getData().total());
        assertEquals("驳回", resp.getData().list().get(0).sampleType());
        verify(coreClient).listSamples(eq(11L), eq("驳回"), eq(2), eq(10),
                eq("created_at"), eq("asc"));
    }

    /** Null sort pair passes through untouched: the bridge invents nothing and
     *  core keeps its historical id DESC default order. */
    @Test
    void listForwardsNullSortPairWithoutInvention() {
        when(coreClient.listSamples(isNull(), isNull(), eq(1), eq(20), isNull(), isNull()))
                .thenReturn(new PageView<>(List.of(), 0L));

        controller.list(null, null, 1, 20, null, null);

        verify(coreClient).listSamples(isNull(), isNull(), eq(1), eq(20), isNull(), isNull());
    }

    
    @Test
    void viewMirrorsAllCoreItemFields() {
        when(coreClient.listSamples(isNull(), isNull(), eq(1), eq(20), isNull(), isNull()))
                .thenReturn(new PageView<>(List.of(new TrainingSampleView(
                        9L, 501L, "TC-0501", 11L, "修改差异",
                        "{\"expected\":\"脱敏后原文\"}", 3, "2026-09-24 20:00:00", 1)), 1L));

        TrainingSampleView row = controller.list(null, null, 1, 20, null, null).getData().list().get(0);

        assertEquals(9L, row.id());
        assertEquals(501L, row.caseId());
        assertEquals("TC-0501", row.caseNo());
        assertEquals(11L, row.projectId());
        assertEquals("修改差异", row.sampleType());
        assertEquals("{\"expected\":\"脱敏后原文\"}", row.sampleJson());
        assertEquals(3, row.sourceVersion());
        assertEquals("2026-09-24 20:00:00", row.createdAt());
        assertEquals(1, row.adopted());
    }

    
    @Test
    void updateAdoptionPassesThroughToCore() {
        Response<Void> resp = controller.updateAdoption(9L, 0);

        assertEquals("0", resp.getCode());
        verify(coreClient).updateAdoption(eq(9L), eq(0));
    }

    
    @Test
    void updateAdoptionPassesAdoptedOneThrough() {
        controller.updateAdoption(12L, 1);

        verify(coreClient).updateAdoption(eq(12L), eq(1));
    }

    
    @Test
    void listLegCarriesDataMaskPermission() throws Exception {
        Method list = TrainingSampleController.class.getMethod("list",
                Long.class, String.class, int.class, int.class, String.class, String.class);
        assertEquals("perm:platform:data-mask",
                list.getAnnotation(RequirePermission.class).value());
    }

    
    @Test
    void adoptionLegCarriesSameDataMaskPermission() throws Exception {
        Method adoption = TrainingSampleController.class.getMethod("updateAdoption",
                Long.class, int.class);
        assertEquals("perm:platform:data-mask",
                adoption.getAnnotation(RequirePermission.class).value());
    }

    private static TrainingSampleView sample(long id, String type) {
        return new TrainingSampleView(id, 501L, "TC-0501", 11L, type,
                "{}", 1, "2026-09-24 20:00:00", 1);
    }
}
