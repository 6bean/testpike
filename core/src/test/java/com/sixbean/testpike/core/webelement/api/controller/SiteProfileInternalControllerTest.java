package com.sixbean.testpike.core.webelement.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileInput;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Internal site-profile door: the controller must stay a thin pass-through
 * onto the site-profile service — every rule (scope derivation, name clash
 * refusal, write-free idempotent save, stale-revision refusal) is decided
 * once inside the service and must not be duplicated here.
 */
class SiteProfileInternalControllerTest {

    private final RecordingSiteProfileService service = new RecordingSiteProfileService();
    private final SiteProfileInternalController controller = new SiteProfileInternalController(service);

    @Test
    void allFiveDoors_delegateToTheSingleService_andWrapSuccess() {
        SiteProfileInput input = new SiteProfileInput(7L, "商城主站", "https://xx.com/pr",
                List.of(), List.of(), null, Map.of(), null, null, null);

        Response<SiteProfileView> created = controller.create(input);
        Response<List<SiteProfileView>> listed = controller.list(7L);
        Response<SiteProfileView> one = controller.get(3L);
        Response<SiteProfileView> updated = controller.update(3L, input);
        Response<Void> deleted = controller.delete(3L);

        assertEquals("0", created.getCode());
        assertEquals("0", listed.getCode());
        assertEquals("0", one.getCode());
        assertEquals("0", updated.getCode());
        assertEquals("0", deleted.getCode());
        assertSame(input, service.lastCreate);
        assertEquals(7L, service.lastListProject);
        assertEquals(3L, service.lastUpdateId);
        assertSame(input, service.lastUpdatePayload);
        assertEquals(3L, service.lastDeleteId);
        assertTrue(service.calls.containsAll(
                List.of("create", "list", "get", "update", "delete")));
    }
}
