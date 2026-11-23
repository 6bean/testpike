package com.sixbean.testpike.core.comment.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.core.comment.api.dto.CommentAttachmentInfo;
import com.sixbean.testpike.core.comment.api.dto.CommentCreateInput;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class CommentAttachmentServiceTest {

    @Autowired
    private CommentService commentService;
    @Autowired
    private CommentAttachmentService attachmentService;
    @Autowired
    private StorageRouter storageRouter;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long author;
    private long commentId;

    // Fixture-scoped teardown : watermark channel for the three
    // non-project comment tables; crashed-run residue is dropped via this
    // suite's author user family before the floors are captured. The old
    // blanket TRUNCATE rewound shared sequences for concurrent writers.
    private long commentFloor;
    private long attachFloor;
    private long readFloor;

    @BeforeEach
    void seed() {
        // tp_comment_read is keyed by (user, host), not by comment — the
        // residue channel for it is this suite's author user family itself.
        String familyUsers = "SELECT id FROM tp_user WHERE username LIKE 'v14a_%'";
        jdbcTemplate.update("DELETE FROM tp_comment_read WHERE user_id IN (" + familyUsers + ")");
        String ownedComments = "author_id IN (" + familyUsers + ")";
        jdbcTemplate.update("DELETE FROM tp_comment_attachment WHERE comment_id IN"
                + " (SELECT id FROM tp_comment WHERE " + ownedComments + ")");
        jdbcTemplate.update("DELETE FROM tp_comment WHERE " + ownedComments);
        commentFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_comment");
        attachFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_comment_attachment");
        readFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_comment_read");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'v14a_%'");
        author = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES ('v14a_author', '$2a$10$x', 'v14a_author', 1, 1) RETURNING id
                """, Long.class);
        commentId = commentService.create(author, new CommentCreateInput("case", 8801L, "带图评论", List.of()));
    }

    @AfterEach
    void sweepRunRows() {
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_comment_attachment", attachFloor);
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_comment_read", readFloor);
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_comment", commentFloor);
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'v14a_%'");
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void 上传落库对象真实可回读() throws Exception {
        byte[] png = pngBytes();
        CommentAttachmentInfo info = attachmentService.upload(commentId, "截图 首页.png", "image/png", png);
        assertNotNull(info.id());
        assertEquals(commentId, info.commentId());
        assertEquals("image/png", info.contentType(), "归一 MIME");
        assertEquals((long) png.length, info.sizeBytes());
        assertTrue(info.fileName().contains(".png"));

        String objectKey = jdbcTemplate.queryForObject(
                "SELECT object_key FROM tp_comment_attachment WHERE id = ?", String.class, info.id());
        assertNotNull(objectKey);
        assertTrue(objectKey.contains("/comment-attachment/"), "key 规范场景段: " + objectKey);
        byte[] back = storageRouter.systemProvider().get(objectKey);
        assertEquals(png.length, back.length, "对象字节回读一致");

        List<CommentAttachmentInfo> listed = attachmentService.listByComment(commentId);
        assertEquals(1, listed.size());
        assertEquals(info.id(), listed.get(0).id());
    }

    @Test
    void 幽灵评论外键400_零落库() throws Exception {
        ServiceException e = assertThrows(ServiceException.class,
                () -> attachmentService.upload(999999L, "a.png", "image/png", pngBytes()));
        assertTrue(e.getMessage().contains("评论"), "人话: " + e.getMessage());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_comment_attachment", Long.class));
    }

    @Test
    void 伪装与超限拒_零行() throws Exception {
        assertThrows(ServiceException.class,
                () -> attachmentService.upload(commentId, "x.png", "image/png",
                        "not an image".getBytes(StandardCharsets.UTF_8)));
        byte[] oversize = new byte[(int) com.sixbean.testpike.common.storage.UploadFileTypes.MAX_ATTACHMENT_BYTES + 1];
        assertThrows(ServiceException.class,
                () -> attachmentService.upload(commentId, "big.png", "image/png", oversize));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_comment_attachment", Long.class), "拒=零行");
    }

    @Test
    void 下载预签名URL非空_幽灵附件404() throws Exception {
        CommentAttachmentInfo info = attachmentService.upload(commentId, "d.png", "image/png", pngBytes());
        StorageGrant grant = attachmentService.download(info.id());
        assertNotNull(grant.url());
        assertTrue(grant.url().startsWith("http"), "可直取 URL: " + grant.url());
        assertEquals(info.objectKey(), grant.key());
        assertThrows(ServiceException.class, () -> attachmentService.download(987654L));
    }
}
