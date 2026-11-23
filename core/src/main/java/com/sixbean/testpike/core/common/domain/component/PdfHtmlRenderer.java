package com.sixbean.testpike.core.common.domain.component;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayOutputStream;


public final class PdfHtmlRenderer {

    private PdfHtmlRenderer() {
    }

    
    public static byte[] render(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(() -> {
                try {
                    return new ClassPathResource("fonts/NotoSansSC.ttf").getInputStream();
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("bundled CJK font could not be loaded", e);
                }
            }, "Noto Sans SC");
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PDF 渲染失败", e);
        }
    }
}
