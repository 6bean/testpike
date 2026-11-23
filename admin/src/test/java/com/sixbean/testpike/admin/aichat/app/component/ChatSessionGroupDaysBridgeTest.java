package com.sixbean.testpike.admin.aichat.app.component;

import com.sixbean.testpike.admin.aichat.api.controller.AiChatSessionController;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Session-history date-group buckets bridge contract :
 * the session panel reads its bucket boundaries from one K8 hot key through
 * the admin→core config door — no second default-value source anywhere.
 * <p>Asserted surface: ①a valid ascending comma list passes through verbatim
 * and the door asks the core client with the pinned key name and the
 * documented default (registration drift = red first); ②every malformed shape
 * (unparsable, blank, non-ascending, non-positive, empty element, null)
 * falls back to the documented 1,7,30 so the panel never renders a broken
 * grouping; ③a core outage degrades to the same default through the fail-soft
 * read posture the SSE heartbeat keys already use; ④the lane carries the
 * catalog door key perm:aichat:use (same member baseline as every other
 * dialog-session door, no new key).
 */
class ChatSessionGroupDaysBridgeTest {

    private final CoreAiChatClient client = mock(CoreAiChatClient.class);
    private final AiChatSessionController controller = new AiChatSessionController(client,
            mock(ProjectAccessGuard.class),
            mock(com.sixbean.testpike.admin.core.infrastructure.remote.client.CoreUserProjectClient.class));

    // ---------- valid value passes through ----------

    @Test
    void ascendingCommaListPassesThroughAsBuckets() {
        when(client.readConfigString(ChatSessionGroupDays.CONFIG_KEY, ChatSessionGroupDays.DEFAULT_SPEC))
                .thenReturn("2, 14");

        Response<Map<String, List<Integer>>> got = controller.groupDays();

        assertEquals(List.of(2, 14), got.getData().get("buckets"));
        // The door asks with the pinned key + documented default — the seed
        // file and this call share that one source.
        verify(client).readConfigString("ai.chat.session-group-days", "1,7,30");
    }

    // ---------- malformed shapes all fall back ----------

    @Test
    void malformedValuesFallBackToTheDocumentedDefault() {
        for (String dirty : List.of("abc", "30,7,1", "1,,7", "-2,7", "0", "7,7", "", "   ")) {
            assertEquals(List.of(1, 7, 30), ChatSessionGroupDays.parseOrFallback(dirty),
                    "malformed spec must fall back: [" + dirty + "]");
        }
        assertEquals(List.of(1, 7, 30), ChatSessionGroupDays.parseOrFallback(null));
    }

    @Test
    void coreOutageReadsAsDefaultBucketsThroughTheDoor() {
        // The rest client already absorbs the outage into the default value;
        // the door must render that default honestly instead of failing.
        when(client.readConfigString(any(), any())).thenReturn(ChatSessionGroupDays.DEFAULT_SPEC);

        assertEquals(List.of(1, 7, 30), controller.groupDays().getData().get("buckets"));
    }

    // ---------- door wiring ----------

    @Test
    void groupDaysDoorCarriesTheDialogKey() throws Exception {
        Method door = AiChatSessionController.class.getMethod("groupDays");
        RequirePermission ann = door.getAnnotation(RequirePermission.class);
        assertNotNull(ann, "group-days door must require a catalog key");
        assertEquals("perm:aichat:use", ann.value(),
                "session-history doors share the one dialog key (no new key this stage)");
    }
}
