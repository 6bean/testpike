package com.sixbean.testpike.aiservice.ocr.domain.service.impl;

import com.sixbean.testpike.aiservice.ocr.api.dto.OcrTextLine;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the bundled OCR worker bridge and the
 * red-annotation pass riding the same envelope .
 *
 * <p>The happy path spawns the real python worker shipped in this repo and
 * runs real CPU inference on a captured fixture image - no mock: the whole
 * point of this bridge is the process boundary and the JSON envelope, and a
 * stub would prove nothing about either. The annotation test draws a real red
 * rectangle over the fixture with Java2D and demands the worker flag the
 * enclosed line while every other line stays unmarked (two-sided fixture).
 */
class OcrEngineServiceImplTest {

    /** Repository-root-relative default must resolve when surefire runs from the module dir. */
    private final OcrEngineServiceImpl service = new OcrEngineServiceImpl(null, null, 120, 800);

    @Test
    void extractReturnsTextLinesWithScoreAndBoxForRealPng() throws IOException {
        List<OcrTextLine> lines = service.extract(probePng());
        assertFalse(lines.isEmpty(), "OCR of a text-bearing panel must yield lines");
        String joined = String.join("\n", lines.stream().map(OcrTextLine::text).toList());
        assertTrue(joined.contains("Local Storage"), "expected panel header in OCR output, got: " + joined);
        assertTrue(joined.contains("abc123"), "expected storage value cell in OCR output, got: " + joined);
        for (OcrTextLine line : lines) {
            assertTrue(line.score() > 0 && line.score() <= 1, "score must be normalized, got " + line.score());
            assertEquals(4, line.box().size(), "each line carries a 4-point box, got " + line.box());
        }
    }

    @Test
    void redBoxDrawnAroundOneLineMarksOnlyThatLineEndToEnd() throws IOException {
        List<OcrTextLine> plain = service.extract(probePng());
        assertTrue(plain.stream().noneMatch(OcrTextLine::annotated),
                "clean panel must report zero annotations (negative half of the two-sided fixture)");
        OcrTextLine target = plain.stream()
                .filter(line -> line.text().contains("abc123"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("fixture line abc123 disappeared from OCR"));
        List<OcrTextLine> marked = service.extract(withRedBoxAround(target.box()));
        OcrTextLine markedTarget = marked.stream()
                .filter(line -> line.text().contains("abc123"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("red box broke OCR of the marked line"));
        assertTrue(markedTarget.annotated(), "line inside the drawn red box must come back annotated");
        assertTrue(marked.stream().filter(line -> line.text().contains("Local Storage"))
                        .noneMatch(OcrTextLine::annotated),
                "lines outside the red box stay unmarked, got: " + describe(marked));
    }

    private static byte[] probePng() throws IOException {
        try (InputStream in = OcrEngineServiceImplTest.class.getResourceAsStream("/ocr/panel-probe.png")) {
            assertTrue(in != null, "fixture /ocr/panel-probe.png must ship with the test tree");
            return in.readAllBytes();
        }
    }

    private static String describe(List<OcrTextLine> lines) {
        return lines.stream().filter(OcrTextLine::annotated)
                .map(line -> line.text() + "@" + line.box()).toList().toString();
    }

    /** Thick red rectangle hugging the line box with a 4px pad, written back as PNG bytes. */
    private static byte[] withRedBoxAround(List<List<Double>> box) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(probePng()));
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (List<Double> point : box) {
            minX = Math.min(minX, point.get(0).intValue());
            maxX = Math.max(maxX, point.get(0).intValue());
            minY = Math.min(minY, point.get(1).intValue());
            maxY = Math.max(maxY, point.get(1).intValue());
        }
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(5f));
        g.drawRect(minX - 4, minY - 4, maxX - minX + 8, maxY - minY + 8);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void missingWorkerFailsClosedWithHonestMessage() {
        OcrEngineServiceImpl broken =
                new OcrEngineServiceImpl("/nonexistent/ocr_worker.py", null, 120, 800);
        byte[] tiny = new byte[] {0};
        ServiceException ex = assertThrows(ServiceException.class, () -> broken.extract(tiny));
        assertTrue(ex.getMessage().contains("ocr worker"),
                "failure must name the worker, got: " + ex.getMessage());
    }

    @Test
    void workerCrashFailsClosedNotSilentEmpty() {
        Path fake = null;
        try {
            fake = Files.createTempFile("fake-ocr-worker", ".py");
            Files.writeString(fake, "import sys\nsys.exit(3)\n");
            OcrEngineServiceImpl crashing = new OcrEngineServiceImpl(fake.toString(), null, 120, 800);
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> crashing.extract(new byte[] {1, 2, 3}));
            assertTrue(ex.getMessage().toLowerCase().contains("worker"),
                    "crash must be reported, got: " + ex.getMessage());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (fake != null) {
                fake.toFile().delete();
            }
        }
    }
}
