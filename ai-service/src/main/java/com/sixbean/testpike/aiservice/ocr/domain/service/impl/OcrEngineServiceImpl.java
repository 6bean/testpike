package com.sixbean.testpike.aiservice.ocr.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrTextLine;
import com.sixbean.testpike.aiservice.ocr.domain.service.OcrEngineService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bridges the bundled python OCR worker over a process boundary
 * (JSON stdin/stdout). Deployment-tunable paths come from the install-time
 * environment (TP_OCR_WORKER_PATH / TP_OCR_PYTHON / TP_OCR_TIMEOUT_S), never
 * hardcoded here.
 */
@Service
public class OcrEngineServiceImpl implements OcrEngineService {

    private static final String WORKER_RELATIVE_PATH = Path.of("native", "ocr", "ocr_worker.py").toString();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workerPath;
    private final String pythonCommand;
    private final long timeoutSeconds;
    private final long redMinAreaPx;

    public OcrEngineServiceImpl(
            @Value("${testpike.ocr.worker-path:#{null}}") String workerPathConfig,
            @Value("${testpike.ocr.python:python3}") String pythonCommand,
            @Value("${testpike.ocr.timeout-seconds:120}") long timeoutSeconds,
            @Value("${testpike.devtools.red-min-area-px:800}") long redMinAreaPx) {
        this.workerPath = resolveWorker(workerPathConfig);
        this.pythonCommand = pythonCommand == null || pythonCommand.isBlank() ? "python3" : pythonCommand;
        this.timeoutSeconds = timeoutSeconds;
        this.redMinAreaPx = redMinAreaPx;
    }

    @Override
    public List<OcrTextLine> extract(byte[] imageBytes) {
        if (workerPath == null || !Files.isRegularFile(workerPath)) {
            throw new ServiceException("ocr worker not found: set TP_OCR_WORKER_PATH to the bundled "
                    + "ocr worker script (expected " + WORKER_RELATIVE_PATH + " under the repository)");
        }
        try {
            Process process = new ProcessBuilder(pythonCommand, workerPath.toString())
                    .redirectErrorStream(false)
                    .start();
            String stdout;
            String stderr;
            try (OutputStream stdin = process.getOutputStream()) {
                ObjectNode request = MAPPER.createObjectNode();
                request.put("image_base64", Base64.getEncoder().encodeToString(imageBytes == null ? new byte[0] : imageBytes));
                request.put("red_min_area_px", redMinAreaPx);
                MAPPER.writeValue(stdin, request);
            }
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                throw new ServiceException("ocr worker timed out after " + timeoutSeconds + "s");
            }
            if (process.exitValue() != 0) {
                throw new ServiceException("ocr worker exited with code " + process.exitValue()
                        + ": " + summarize(stderr));
            }
            return parseLines(stdout);
        } catch (ServiceException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ServiceException("ocr worker invocation failed: " + e.getMessage());
        }
    }

    private List<OcrTextLine> parseLines(String stdout) throws IOException {
        JsonNode root = MAPPER.readTree(stdout);
        List<OcrTextLine> lines = new ArrayList<>();
        for (JsonNode node : root.path("lines")) {
            List<List<Double>> box = new ArrayList<>();
            for (JsonNode point : node.path("box")) {
                List<Double> xy = new ArrayList<>(2);
                point.forEach(coordinate -> xy.add(coordinate.asDouble()));
                box.add(xy);
            }
            lines.add(new OcrTextLine(
                    node.path("text").asText(),
                    parseScore(node.path("score").asText()),
                    List.copyOf(box),
                    // Absent flag means an older worker without the red pass;
                    // "nothing marked" is the only safe reading there.
                    node.path("annotated").asBoolean(false)));
        }
        return List.copyOf(lines);
    }

    /** The engine reports confidence as a decimal string; malformed means a contract break, fail closed. */
    private double parseScore(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new ServiceException("ocr worker returned a non-numeric confidence: " + raw);
        }
    }

    private String summarize(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "no stderr output";
        }
        String trimmed = stderr.trim();
        return trimmed.length() > 400 ? trimmed.substring(0, 400) : trimmed;
    }

    /** Explicit config wins; otherwise probe the bundled script from likely working directories. */
    private static Path resolveWorker(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(WORKER_RELATIVE_PATH);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            Path moduleScoped = dir.resolve(Path.of("ai-service", WORKER_RELATIVE_PATH));
            if (Files.isRegularFile(moduleScoped)) {
                return moduleScoped;
            }
        }
        return null;
    }
}
