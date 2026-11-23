package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.common.aichat.ChatImageAttachments;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.core.aichat.api.dto.ChatImageInfo;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chat user-image upload door . Real dev DB +
 * real storage (no mocks): the door must mint keys in exactly the canonical
 * chat-image shape {@link ChatImageAttachments} accepts at the run door,
 * write real readable bytes under the issuing project's segment, and refuse
 * every malformed upload before a single object lands (the same validate-
 * first posture as comment attachments — a refused upload leaves zero
 * bytes behind).
 */
@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class ChatImageUploadServiceTest {

    @Autowired
    private ChatImageService chatImageService;
    @Autowired
    private StorageRouter storageRouter;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void seedProject() {
        // tp_project.code is varchar(20) — keep the unique tail, not the full clock.
        String tail = String.valueOf(System.nanoTime() % 1_000_000_000_000L);
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "chat-image-test-" + tail, "chatimg-" + tail);
    }

    @AfterEach
    void cleanProject() {
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    @Test
    void uploadMintsCanonicalKeyUnderProjectSliceAndReadableBytes() throws IOException {
        byte[] png = pngBytes();
        ChatImageInfo info = chatImageService.upload(projectId, "登录页截图.png", "image/png", png);
        assertNotNull(info.objectKey());
        // The run-door validator must accept the minted key unchanged (canonical shape).
        List<ChatImageAttachment> canonical = ChatImageAttachments.canonicalize(
                List.of(new ChatImageAttachment(info.objectKey(), info.fileName())), 4);
        assertEquals(1, canonical.size());
        assertEquals(projectId, ChatImageAttachments.projectIdOf(info.objectKey()));
        // Bytes really landed and are readable back through the same provider.
        assertTrue(storageRouter.systemProvider().exists(info.objectKey()));
        assertEquals(png.length, storageRouter.systemProvider().get(info.objectKey()).length);
        StorageGrant grant = storageRouter.systemProvider()
                .presignGet(info.objectKey(), storageRouter.urlTtl());
        assertNotNull(grant.url());
        assertEquals("image/png", info.contentType());
        assertEquals(png.length, info.sizeBytes());
    }

    @Test
    void missingProjectIsRefusedBeforeAnyObjectLands() throws IOException {
        long ghostProject = 999_999_999L;
        ServiceException refusal = assertThrows(ServiceException.class, () ->
                chatImageService.upload(ghostProject, "a.png", "image/png", pngBytes()));
        assertTrue(refusal.getMessage().contains("项目不存在"), refusal.getMessage());
    }

    @Test
    void nonImageExtensionIsRefused() {
        ServiceException refusal = assertThrows(ServiceException.class, () ->
                chatImageService.upload(projectId, "notes.txt", "text/plain",
                        "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(refusal.getMessage().contains("格式不支持"), refusal.getMessage());
    }

    @Test
    void spoofedExtensionIsRefused() throws IOException {
        // Real PNG bytes wearing a .jpg extension: magic-vs-extension gate refuses.
        ServiceException refusal = assertThrows(ServiceException.class, () ->
                chatImageService.upload(projectId, "fake.jpg", "image/jpeg", pngBytes()));
        assertTrue(refusal.getMessage().contains("不符"), refusal.getMessage());
    }

    @Test
    void overCapContentIsRefused() {
        byte[] oversized = new byte[(int) com.sixbean.testpike.common.storage
                .UploadFileTypes.MAX_ATTACHMENT_BYTES + 1];
        // PNG magic so only the size gate can refuse (extension check passes first).
        System.arraycopy(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, 0, oversized, 0, 4);
        ServiceException refusal = assertThrows(ServiceException.class, () ->
                chatImageService.upload(projectId, "big.png", "image/png", oversized));
        assertTrue(refusal.getMessage().contains("超过限制"), refusal.getMessage());
    }

    @Test
    void emptyContentIsRefused() {
        ServiceException refusal = assertThrows(ServiceException.class, () ->
                chatImageService.upload(projectId, "empty.png", "image/png", new byte[0]));
        assertTrue(refusal.getMessage().contains("为空"), refusal.getMessage());
    }

    private static byte[] pngBytes() throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
