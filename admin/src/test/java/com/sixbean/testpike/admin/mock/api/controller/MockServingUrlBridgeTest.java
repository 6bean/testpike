package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.auth.infrastructure.remote.client.ConfigValueClient;
import com.sixbean.testpike.admin.mock.api.dto.MockServingUrlView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Serving-URL admin bridge contract test the workbench URL
 * card door).
 * <p>Asserted surface: ①both K8 config values (public base url + traffic base
 * path) pass through the bridge verbatim — the URL card must show the address
 * the traffic plane is actually deployed with, never a bridge-invented host;
 * ②an unset public base url stays blank (honest empty: the workbench words the
 * gap in plain language instead of printing a dead link);
 * ③the lane carries the read key perm:mock:read — assembling a display URL is a
 * viewing act, the same door family as the space list.
 * <p>ConfigValueClient stand-in = L2 bridge-contract isolation (precedent:
 * MockLoadStateBridgeTest); the real admin→core /internal/configs/value chain is
 * verified at L3 together with the workbench front-end pack.
 */
class MockServingUrlBridgeTest {

    private final ConfigValueClient config = mock(ConfigValueClient.class);
    private final MockServingUrlController controller = new MockServingUrlController(config);

    @Test
    void servingUrl_passesBothConfiguredValuesVerbatim() {
        when(config.getValue(eq("mock.public-base-url"), anyString())).thenReturn("http://mock.example:18086");
        when(config.getValue(eq("mock.traffic.base-path"), anyString())).thenReturn("/mocks");

        MockServingUrlView view = controller.servingUrl().getData();
        assertEquals("http://mock.example:18086", view.publicBaseUrl());
        assertEquals("/mocks", view.trafficBasePath());
    }

    @Test
    void unsetPublicBaseUrl_staysBlankNoInventedHost() {
        // Core answers "" when the key holds an empty value; getValue's own
        // default fallback only fires on unreachable core, so the bridge must
        // forward the blank verbatim instead of dressing it up.
        when(config.getValue(eq("mock.public-base-url"), anyString())).thenReturn("");
        when(config.getValue(eq("mock.traffic.base-path"), anyString())).thenReturn("/mocks");

        MockServingUrlView view = controller.servingUrl().getData();
        assertEquals("", view.publicBaseUrl(),
                "unset = honest blank — the UI words the gap, the bridge never fabricates a host");
    }

    @Test
    void servingUrlLane_carriesReadKeySameDoorAsSpaceList() throws NoSuchMethodException {
        Method m = MockServingUrlController.class.getMethod("servingUrl");
        RequirePermission ann = m.getAnnotation(RequirePermission.class);
        assertEquals("perm:mock:read", ann == null ? null : ann.value(),
                "assembling the URL card is a viewing act — same door as the space list");
    }
}
