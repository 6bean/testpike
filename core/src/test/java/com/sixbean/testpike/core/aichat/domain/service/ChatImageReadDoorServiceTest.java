package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatImageInfo;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chat user-image read door . Real dev DB + real
 * storage, no mocks: core is the SINGLE ownership point of the channel — the
 * ai-service vision leg never touches object storage directly, it asks this
 * door for a presigned GET url and the door refuses anything outside the
 * requesting project's segment before a url is ever minted.
 * <p>Assertions:
 * ① a canonical key under the issuing project returns a presigned url;
 * ② a key issued under another project is refused (H1 horizontal access);
 * ③ a malformed key (any other storage scene) is refused by name — the
 * channel reads chat-image keys only;
 * ④ a key of the right shape whose object is gone is refused, not presigned
 * (a dead url handed to the model would degrade the whole turn later).
 */
@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class ChatImageReadDoorServiceTest {

    @Autowired
    private ChatImageService chatImageService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void seedProject() {
        // tp_project.code is varchar(20) — keep the unique tail, not the full clock.
        String tail = String.valueOf(System.nanoTime() % 1_000_000_000_000L);
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "chat-read-test-" + tail, "chatrd-" + tail);
    }

    @AfterEach
    void cleanProject() {
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    @Test
    void canonicalKeyUnderTheIssuingProjectReturnsAPresignedUrl() throws IOException {
        ChatImageInfo info = chatImageService.upload(projectId, "报错页.png", "image/png", pngBytes());

        String url = chatImageService.presignReadUrl(projectId, info.objectKey());

        assertNotNull(url);
        assertFalse(url.isBlank(), "presigned url expected, got: " + url);
    }

    @Test
    void keyIssuedUnderAnotherProjectIsRefused() throws IOException {
        ChatImageInfo info = chatImageService.upload(projectId, "私有截图.png", "image/png", pngBytes());
        long otherProject = 999_999_998L;

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> chatImageService.presignReadUrl(otherProject, info.objectKey()));

        assertTrue(refusal.getMessage().contains("不属于"), refusal.getMessage());
    }

    @Test
    void foreignStorageSceneIsRefusedByName() {
        String execArtifactKey = projectId + "/exec-artifact/202610/ab12-report.pdf";

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> chatImageService.presignReadUrl(projectId, execArtifactKey));

        assertTrue(refusal.getMessage().contains("格式"), refusal.getMessage());
    }

    @Test
    void canonicalKeyWithMissingObjectIsRefusedNotPresigned() {
        String goneKey = projectId + "/chat-image/202610/zz99-已清理.png";

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> chatImageService.presignReadUrl(projectId, goneKey));

        assertTrue(refusal.getMessage().contains("不存在") || refusal.getMessage().contains("已清理"),
                refusal.getMessage());
    }

    private static byte[] pngBytes() throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
