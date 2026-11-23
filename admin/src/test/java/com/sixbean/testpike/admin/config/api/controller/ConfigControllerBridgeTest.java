package com.sixbean.testpike.admin.config.api.controller;

import com.sixbean.testpike.admin.config.api.dto.ConfigSaveRequest;
import com.sixbean.testpike.admin.config.api.dto.ConfigView;
import com.sixbean.testpike.admin.config.api.dto.RetentionItemView;
import com.sixbean.testpike.admin.config.infrastructure.remote.client.StubConfigClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admin bridge contract for the system-config endpoints (K8): the controller must
 * delegate 1:1 to the core client and surface the display fields unchanged — the
 * operation-info description ("default / range / hot-reload" parenthetical) and the
 * masked sensitive value are the user-visible contract of the /configs page, so any
 * loss or rewriting while crossing the bridge is a regression.
 */
class ConfigControllerBridgeTest {

    private static final String SCRUBBED_DESCRIPTION =
            "Batch review pass rate threshold (default 0.8; range 0.5~1.0; hot-reload takes effect immediately)";

    private final StubConfigClient client = new StubConfigClient();
    private final ConfigController controller = new ConfigController(client);

    @Test
    void listPassesGroupFilterAndKeepsDisplayFieldsIntact() {
        client.seed(new ConfigView(1L, "review.pass-rate.threshold", "0.8", "AI",
                "string", 0, SCRUBBED_DESCRIPTION));
        client.seed(new ConfigView(2L, "llm.api.secret", "******", "AI",
                "string", 1, "LLM provider key (stored encrypted; masked in list)"));

        Response<List<ConfigView>> resp = controller.list("AI");

        assertEquals("0", resp.getCode());
        assertEquals(List.of("AI"), client.listGroupCalls(), "group filter must reach core unchanged");
        List<ConfigView> rows = resp.getData();
        assertEquals(2, rows.size());
        assertEquals(SCRUBBED_DESCRIPTION, rows.get(0).description(),
                "operation-info description must surface unchanged");
        assertEquals("******", rows.get(1).configValue(),
                "masked sensitive value must stay masked across the bridge");
        assertEquals(1, rows.get(1).encryptFlag());
    }

    @Test
    void listWithoutGroupStillDelegatesWithNull() {
        Response<List<ConfigView>> resp = controller.list(null);

        assertEquals("0", resp.getCode());
        assertEquals(1, client.listGroupCalls().size());
        assertNull(client.listGroupCalls().get(0), "absent filter must forward as null, not empty string");
        assertTrue(resp.getData().isEmpty());
    }

    @Test
    void createForwardsBodyAndReturnsEchoedView() {
        ConfigSaveRequest body = new ConfigSaveRequest("case.retry.max", "3", "系统",
                "int", 0, "Max case retries per run (default 3; range 0~10; hot-reload)");

        ConfigView created = controller.create(body).getData();

        assertEquals(body, client.lastCreate());
        assertEquals("case.retry.max", created.configKey());
        assertEquals(100L, created.id(), "server-assigned id must surface to the caller");
        assertEquals("Max case retries per run (default 3; range 0~10; hot-reload)",
                created.description());
    }

    @Test
    void updateCarriesRowIdAndBodyUnchanged() {
        ConfigSaveRequest body = new ConfigSaveRequest("notify.channel", "站内信", "通知",
                "string", 0, "Notification delivery channel (default in-app; hot-reload)");

        ConfigView updated = controller.update(7L, body).getData();

        assertEquals(7L, client.lastUpdateId());
        assertEquals(body, client.lastUpdate());
        assertEquals(7L, updated.id());
        assertFalse(updated.description().isEmpty());
    }

    @Test
    void deleteForwardsIdAndReturnsEmptySuccess() {
        Response<Void> resp = controller.delete(9L);

        assertEquals(List.of(9L), client.deleteCalls());
        assertEquals("0", resp.getCode());
        assertNull(resp.getData());
    }

    @Test
    void retentionPreviewAndRunExposeItemViews() {
        List<RetentionItemView> preview = controller.retentionPreview().getData();
        List<RetentionItemView> run = controller.retentionRun().getData();

        assertEquals(1, preview.size());
        assertEquals(preview.get(0).configKey(), run.get(0).configKey());
        assertEquals(90, preview.get(0).days());
        assertEquals("retention.audit.days", preview.get(0).configKey());
    }
}
