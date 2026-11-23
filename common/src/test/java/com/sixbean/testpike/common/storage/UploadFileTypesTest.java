package com.sixbean.testpike.common.storage;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class UploadFileTypesTest {

    private static byte[] imageBytes(String format) throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @Test
    void 合法图片归一MIME返回() throws Exception {
        assertEquals("image/png",
                UploadFileTypes.validateAttachment("shot.png", "image/PNG; charset=x", imageBytes("png")));
        assertEquals("image/jpeg",
                UploadFileTypes.validateAttachment("a.jpg", "image/jpeg", imageBytes("jpg")));
        assertEquals("image/gif",
                UploadFileTypes.validateAttachment("a.gif", null, imageBytes("gif")));
        assertEquals("image/webp", UploadFileTypes.validateAttachment("a.webp", "image/webp",
                webpBytes()));
    }

    
    private static byte[] webpBytes() {
        byte[] body = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, body, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, body, 8, 4);
        System.arraycopy("VP8 ".getBytes(StandardCharsets.US_ASCII), 0, body, 12, 4);
        return body;
    }

    @Test
    void 伪装MIME拒_声明与魔数不符() throws Exception {
        ServiceException fake = assertThrows(ServiceException.class,
                () -> UploadFileTypes.validateAttachment("x.png", "image/png",
                        "MZ this is not an image".getBytes(StandardCharsets.UTF_8)));
        assertTrue(fake.getMessage().contains("不是"), "人话提示: " + fake.getMessage());
        assertThrows(ServiceException.class,
                () -> UploadFileTypes.validateAttachment("x.png", "image/png", imageBytes("gif")));
        assertThrows(ServiceException.class,
                () -> UploadFileTypes.validateAttachment("x.png", "application/x-msdownload",
                        imageBytes("png")));
    }

    @Test
    void 扩展名白名单外拒_空文件拒() throws Exception {
        byte[] png = imageBytes("png");
        ServiceException ext = assertThrows(ServiceException.class,
                () -> UploadFileTypes.validateAttachment("evil.exe", "image/png", png));
        assertTrue(ext.getMessage().contains("png") || ext.getMessage().contains("格式"),
                "人话含可选格式: " + ext.getMessage());
        assertEquals("image/png",
                UploadFileTypes.validateAttachment("../../etc/passwd.png", "image/png", png));
        assertThrows(ServiceException.class,
                () -> UploadFileTypes.validateAttachment("e.png", "image/png", new byte[0]));
    }

    @Test
    void 体积超限拒_人话含上限() throws Exception {
        byte[] oversize = new byte[(int) UploadFileTypes.MAX_ATTACHMENT_BYTES + 1];
        java.util.Arrays.fill(oversize, (byte) 'A');
        ServiceException size = assertThrows(ServiceException.class,
                () -> UploadFileTypes.validateAttachment("big.png", "image/png", oversize));
        assertTrue(size.getMessage().contains("10"), "文案含上限值（红线10）: " + size.getMessage());
    }
}
