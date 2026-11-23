package com.sixbean.testpike.admin.comment.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.comment.api.dto.CommentAttachmentView;
import com.sixbean.testpike.admin.comment.api.dto.CommentCreateRequest;
import com.sixbean.testpike.admin.comment.api.dto.CommentUnreadView;
import com.sixbean.testpike.admin.comment.api.dto.CommentView;
import com.sixbean.testpike.admin.comment.infrastructure.remote.client.CoreCommentClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.storage.StorageGrant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class CommentBridgeTest {

    private final CoreCommentClient client = mock(CoreCommentClient.class);
    private final CommentController controller = new CommentController(client);
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static void login(long userId) {
        UserContext.set(new CurrentUser(userId, "u" + userId, "U", "测试人",
                List.of("TESTER"), System.currentTimeMillis() + 3600_000, null));
    }


    @Test
    void 评论视图九字段与core契约对齐不静默丢() throws Exception {
        String coreJson = """
                {"id":101,"hostType":"defect","hostId":9,"source":1,"authorId":7,
                 "authorName":"张三","content":"复现步骤补充","refKey":null,
                 "createdAt":"2026-09-21 10:00:00"}""";
        CommentView view = mapper.readValue(coreJson, CommentView.class);
        assertEquals(101L, view.id());
        assertEquals("defect", view.hostType());
        assertEquals(9L, view.hostId());
        assertEquals(1, view.source());
        assertEquals(7L, view.authorId());
        assertEquals("张三", view.authorName());
        assertEquals("复现步骤补充", view.content());
        assertEquals(null, view.refKey());
        assertEquals("2026-09-21 10:00:00", view.createdAt());
    }

    @Test
    void 附件与未读视图字段与core契约对齐() throws Exception {
        String attJson = """
                {"id":5,"commentId":101,"fileName":"截图.png","objectKey":"tp/0/comment-attachment/202609/a1-截图.png",
                 "contentType":"image/png","sizeBytes":20480,"createdAt":"2026-09-21 10:01:00"}""";
        CommentAttachmentView att = mapper.readValue(attJson, CommentAttachmentView.class);
        assertEquals(5L, att.id());
        assertEquals(101L, att.commentId());
        assertEquals("截图.png", att.fileName());
        assertEquals("image/png", att.contentType());
        assertEquals(20480L, att.sizeBytes());

        String unreadJson = """
                {"hostType":"case","hostId":66,"unreadCount":3}""";
        CommentUnreadView unread = mapper.readValue(unreadJson, CommentUnreadView.class);
        assertEquals("case", unread.hostType());
        assertEquals(66L, unread.hostId());
        assertEquals(3, unread.unreadCount());
    }


    @Test
    void 列表参数按位透传core() {
        login(7L);
        when(client.list("review", 66L)).thenReturn(List.of());
        Response<List<CommentView>> resp = controller.list("review", 66L);
        assertEquals("0", resp.getCode());
        verify(client).list("review", 66L);
    }

    @Test
    void 创建评论登录态userId透传且返回新id() {
        login(7L);
        when(client.create(eq(7L), any(CommentCreateRequest.class))).thenReturn(101L);
        CommentCreateRequest req = new CommentCreateRequest("case", 66L, "正文 @张三", List.of(8L));
        Response<Map<String, Long>> resp = controller.create(req);
        assertEquals(101L, resp.getData().get("id"));
        verify(client).create(eq(7L), eq(req));
    }

    @Test
    void 未读角标与标记已读登录态userId透传() {
        login(7L);
        when(client.unreadCounts(eq(7L), eq("defect"), eq(List.of(1L, 2L))))
                .thenReturn(List.of(new CommentUnreadView("defect", 2L, 1)));
        Response<List<CommentUnreadView>> unread = controller.unreadCounts("defect", List.of(1L, 2L));
        assertEquals(1, unread.getData().size());
        verify(client).unreadCounts(7L, "defect", List.of(1L, 2L));

        controller.markRead("defect", 2L);
        verify(client).markRead(7L, "defect", 2L);
    }


    @Test
    void 附件上传字节零丢失透传() throws IOException {
        login(7L);
        byte[] bytes = "PNG-stub-bytes-含中文ûnicode".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "截图.png", "image/png", bytes);
        when(client.uploadAttachment(eq(101L), eq("截图.png"), eq("image/png"), any(byte[].class)))
                .thenReturn(new CommentAttachmentView(5L, 101L, "截图.png",
                        "tp/0/comment-attachment/202609/a1-截图.png", "image/png", bytes.length,
                        "2026-09-21 10:01:00"));
        Response<CommentAttachmentView> resp = controller.upload(101L, file);
        assertEquals(5L, resp.getData().id());
        var captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(client).uploadAttachment(eq(101L), eq("截图.png"), eq("image/png"), captor.capture());
        assertArrayEquals(bytes, captor.getValue());
    }

    @Test
    void 附件列表与下载预签名grant直出透传() {
        login(7L);
        when(client.listAttachments(101L)).thenReturn(List.of());
        controller.listAttachments(101L);
        verify(client).listAttachments(101L);

        StorageGrant grant = new StorageGrant("tp/0/comment-attachment/202609/a1-x.png",
                "http://localhost:8081/storage/files/tp/0/comment-attachment/202609/a1-x.png?expires=1&signature=s");
        when(client.downloadGrant(5L)).thenReturn(grant);
        Response<StorageGrant> resp = controller.download(5L);
        assertNotNull(resp.getData());
        assertEquals(grant.url(), resp.getData().url());
        assertEquals(grant.key(), resp.getData().key());
        verify(client).downloadGrant(5L);
    }


    @Test
    void 七端点全部挂perm评论键() {
        int annotated = 0;
        for (Method m : CommentController.class.getDeclaredMethods()) {
            RequirePermission rp = m.getAnnotation(RequirePermission.class);
            if (rp != null) {
                assertEquals("perm:comment:use", rp.value(), m.getName());
                annotated++;
            }
        }
        assertEquals(7, annotated, "七端点=③读①写②已读未读②附件（list/create/unread/markRead/upload/listAttachments/download）");
        assertTrue(CommentController.class.isAnnotationPresent(RestController.class));
    }

    
    @Test
    void 客户端异常人话由core单点直出桥层不拦截() {
        login(7L);
        when(client.list(anyString(), anyLong()))
                .thenThrow(new com.sixbean.testpike.common.exception.ServiceException("宿主类型不支持: xxx", 40001));
        try {
            controller.list("xxx", 1L);
            org.junit.jupiter.api.Assertions.fail("应上抛 core 人话");
        } catch (com.sixbean.testpike.common.exception.ServiceException e) {
            assertTrue(e.getMessage().contains("宿主类型不支持"));
        }
    }
}
