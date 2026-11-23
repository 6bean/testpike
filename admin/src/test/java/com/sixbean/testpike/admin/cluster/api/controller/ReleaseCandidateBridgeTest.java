package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.cluster.api.dto.ReleaseCandidateDismissRequest;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseCandidateView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseManifestView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseRegisterRequest;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreReleaseCandidateClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Release candidate admin bridge contract test .
 * <p>Asserted surface: ①list/pending-count read projection passes through the
 * core frame verbatim (status spectrum/payload never truncated — the frontend
 * renders honest empty vs pending states from these fields, the bridge must not
 * re-judge); ②confirm forwards candidate id + the full seven-field register
 * input untouched (validation single point lives in core, no second check here —
 * red line 6); ③dismiss carries the plain-word reason through verbatim (core
 * rejects blanks, the bridge must not swallow it); ④core ServiceException with
 * a human message propagates untouched (no rewriting, no masking).
 * <p>CoreReleaseCandidateClient stand-in = L2 bridge-contract isolation (precedent:
 * ReleaseManifestBridgeTest); the real admin→core /internal/cluster/release-candidate
 * chain is verified at L3.
 */
class ReleaseCandidateBridgeTest {

    private final CoreReleaseCandidateClient client = mock(CoreReleaseCandidateClient.class);
    private final ReleaseCandidateController controller = new ReleaseCandidateController(client);

    @Test
    void 候选列表_八字段完整投影_降序原样透出() {
        LocalDateTime now = LocalDateTime.now();
        when(client.listPending()).thenReturn(List.of(
                new ReleaseCandidateView(2L, "1.5.0", "released", "poll", now,
                        "{\"release_name\":\"v1.5.0\"}", "detected", null),
                new ReleaseCandidateView(1L, "1.4.0", "prerelease", "webhook", now,
                        "{\"dismiss_reason\":\"节奏不合\"}", "dismissed", 9L)));
        List<ReleaseCandidateView> all = controller.list().getData();
        assertEquals(2, all.size());
        ReleaseCandidateView head = all.get(0);
        assertEquals("1.5.0", head.version());
        assertEquals("released", head.channel());
        assertEquals("poll", head.detectedSource());
        assertTrue(head.payloadJson().contains("release_name"), "探测原始帧不截断");
        assertEquals("detected", head.status());
        assertEquals(null, head.confirmedManifestId(), "未确认=null 诚实空位");
        assertEquals(9L, all.get(1).confirmedManifestId());
    }

    @Test
    void 角标计数_原样透出_zero不吞() {
        when(client.pendingCount()).thenReturn(0);
        assertEquals(0, controller.pendingCount().getData().intValue(),
                "零候选=诚实 0（桥面禁吞 null 谎报）");
        when(client.pendingCount()).thenReturn(3);
        assertEquals(3, controller.pendingCount().getData().intValue());
    }

    @Test
    void 确认桥_候选id与七字段登记入参原样透传() {
        when(client.confirm(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any())).thenReturn(
                new ReleaseManifestView(5L, "1.5.0", "[]", "[]", null, null, null, "import",
                        LocalDateTime.now(), "[]", null, null));

        controller.confirm(7L, new ReleaseRegisterRequest("1.5.0",
                "[{\"component\":\"executor-agent\"}]", "[\"协议变更句\"]",
                "1.4.0", "1.6.0", "跳版限制句", "import", null, null));

        ArgumentCaptor<ReleaseRegisterRequest> captor =
                ArgumentCaptor.forClass(ReleaseRegisterRequest.class);
        verify(client).confirm(org.mockito.ArgumentMatchers.eq(7L), captor.capture());
        ReleaseRegisterRequest sent = captor.getValue();
        assertEquals("1.5.0", sent.version());
        assertEquals("1.4.0", sent.executorVersionMin());
        assertEquals("跳版限制句", sent.suggestedPath(), "登记入参零加工透传（转正校验单源 core）");
    }

    @Test
    void 作废桥_理由原样透传() {
        when(client.dismiss(org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.any())).thenReturn(
                new ReleaseCandidateView(3L, "1.3.0", "released", "poll", LocalDateTime.now(),
                        "{}", "dismissed", null));
        controller.dismiss(3L, new ReleaseCandidateDismissRequest("上游撤回该版本"));
        verify(client).dismiss(3L, "上游撤回该版本");
    }

    @Test
    void core人话异常_原样上抛不吞() {
        when(client.confirm(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any())).thenThrow(
                new ServiceException("确认发布的版本号与候选版本不一致——防误点错版", null));
        ServiceException thrown = assertThrows(ServiceException.class,
                () -> controller.confirm(1L, new ReleaseRegisterRequest("9.9.9",
                        null, null, null, null, null, null, null, null)));
        assertTrue(thrown.getMessage().contains("不一致"), "core 人话句原样透出");

        when(client.dismiss(org.mockito.ArgumentMatchers.eq(2L),
                org.mockito.ArgumentMatchers.any())).thenThrow(
                new ServiceException("作废候选必须填写理由", null));
        assertThrows(ServiceException.class,
                () -> controller.dismiss(2L, new ReleaseCandidateDismissRequest(" ")));
    }
}
