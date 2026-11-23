package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementCaptureRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementInput;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.component.MonitorFrameBuffer;
import com.sixbean.testpike.core.appauto.domain.service.AppautoElementCaptureService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoElementService;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live-frame crop capture (see {@link AppautoElementCaptureService}).
 * <p>Capture path: read the newest buffered monitor frame -> decode it ->
 * validate and cut the selected region -> render each requested scale ->
 * archive the images through the project storage route -> file the image
 * element profile via the element library service (validation and the
 * per-project unique name rule are not duplicated here).
 */
@Service
public class AppautoElementCaptureServiceImpl implements AppautoElementCaptureService {

    /** Storage scene registered in {@code StorageKeys.SCENES} for captured element crops. */
    private static final String STORAGE_SCENE = "appauto-element";

    /** Scale factor ceiling per capture (beyond template-matching usefulness). */
    private static final double MAX_SCALE_FACTOR = 4.0;

    private final MonitorFrameBuffer frameBuffer;
    private final AppautoElementService elementService;
    private final ArtifactStorage artifactStorage;
    private final AppautoConfigReader configReader;

    public AppautoElementCaptureServiceImpl(MonitorFrameBuffer frameBuffer,
                                            AppautoElementService elementService,
                                            ArtifactStorage artifactStorage,
                                            AppautoConfigReader configReader) {
        this.frameBuffer = frameBuffer;
        this.elementService = elementService;
        this.artifactStorage = artifactStorage;
        this.configReader = configReader;
    }

    @Override
    public long captureFromFrame(long projectId, AppautoElementCaptureRequest request) {
        int frameIndex = request.frameIndex() == null ? 0 : request.frameIndex();
        BufferedImage frame = decodeFrame(request.taskId(), frameIndex);
        int x = request.x();
        int y = request.y();
        int width = request.width();
        int height = request.height();
        validateRegion(frame, x, y, width, height);

        List<Double> scales = resolveScales(request.scales());
        int maxVariants = configReader.elementCaptureMaxVariants();
        if (scales.size() > maxVariants) {
            throw new ServiceException("一次取图最多生成 " + maxVariants + " 个倍率变体，当前请求 "
                    + scales.size() + " 个，请精简倍率后重试，或到「系统设置」的 APP 自动化参数调高单次变体上限",
                    BizErrorCode.VALIDATION_ERROR);
        }

        BufferedImage crop = frame.getSubimage(x, y, width, height);
        List<Map<String, Object>> variants = new ArrayList<>();
        for (Double scale : scales) {
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            byte[] png = toPng(render(crop, targetWidth, targetHeight));
            String objectKey = artifactStorage.buildProjectObjectKey(projectId, STORAGE_SCENE,
                    "capture-" + formatScale(scale) + ".png");
            artifactStorage.putBytesForProject(projectId, objectKey, png, "image/png");
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("resolution", targetWidth + "x" + targetHeight);
            variant.put("artifactKey", objectKey);
            variant.put("scale", scale);
            variants.add(variant);
        }

        Map<String, Object> region = new LinkedHashMap<>();
        region.put("x", x);
        region.put("y", y);
        region.put("width", width);
        region.put("height", height);
        Map<String, Object> locatorConfig = new LinkedHashMap<>();
        locatorConfig.put("taskId", request.taskId());
        locatorConfig.put("frameIndex", frameIndex);
        locatorConfig.put("region", region);

        // A capture sent without an explicit floor files the shipped element-table
        // default; any supplied value still passes the single-point range validation
        // in the archive service (this fallback lives in the capture use case only).
        Double confidence = request.confidenceThreshold() == null
                ? AppautoElementService.DEFAULT_CONFIDENCE_FLOOR
                : request.confidenceThreshold();
        return elementService.create(projectId, new AppautoElementInput(request.name(),
                AppautoElementService.TYPE_IMAGE, locatorConfig, variants, confidence));
    }

    /** Newest-first buffered frames -> one decoded image; honest errors name the live frame gap. */
    private BufferedImage decodeFrame(long taskId, int frameIndex) {
        Map<String, Object> view = frameBuffer.readFrames(taskId);
        Object raw = view.get("frames");
        List<?> frames = raw instanceof List<?> list ? list : List.of();
        if (frames.isEmpty()) {
            throw new ServiceException("任务 #" + taskId + " 暂无实时帧——请先在「执行监看」确认设备正在推流，再点「框选取图」重新取图",
                    BizErrorCode.NOT_FOUND);
        }
        if (frameIndex < 0 || frameIndex >= frames.size()) {
            throw new ServiceException("实时帧序号 #" + frameIndex + " 不存在：帧环当前仅保留 "
                    + frames.size() + " 帧（0=最新，越大越旧）——请在「执行监看」重新框选最新帧",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Object frame = frames.get(frameIndex);
        Object image = frame instanceof Map<?, ?> map ? map.get("image") : null;
        if (!(image instanceof String text) || text.isBlank()) {
            throw new ServiceException("实时帧 #" + frameIndex + " 缺少图像数据——请在「执行监看」等到下一帧推送后重试",
                    BizErrorCode.NOT_FOUND);
        }
        String payload = text.startsWith("data:") ? text.substring(text.indexOf(',') + 1) : text;
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(payload)));
        } catch (Exception e) {
            throw new ServiceException("实时帧 #" + frameIndex + " 图像解码失败——请在「执行监看」等到下一帧推送后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (decoded == null) {
            throw new ServiceException("实时帧 #" + frameIndex + " 不是可识别的图像格式——请在「执行监看」等到下一帧推送后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return decoded;
    }

    private static void validateRegion(BufferedImage frame, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new ServiceException("框选尺寸必须为正：当前 width=" + width + ", height=" + height
                    + "，请在「执行监看」画面上重新拖拽选框", BizErrorCode.VALIDATION_ERROR);
        }
        if (x < 0 || y < 0 || x + width > frame.getWidth() || y + height > frame.getHeight()) {
            throw new ServiceException("框选区域超出实时帧画面：画面尺寸 " + frame.getWidth() + "x"
                    + frame.getHeight() + "，选框 x=" + x + ", y=" + y + ", width=" + width
                    + ", height=" + height + "，请在「执行监看」画面范围内重新拖拽选框",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Requested scales honour their order; an empty list falls back to the hot-key default set. */
    private List<Double> resolveScales(List<Double> requested) {
        if (requested == null || requested.isEmpty()) {
            return configReader.elementCaptureDefaultScales();
        }
        for (Double scale : requested) {
            if (scale == null || scale <= 0 || scale > MAX_SCALE_FACTOR) {
                throw new ServiceException("倍率必须是 0 到 " + formatScale(MAX_SCALE_FACTOR)
                        + " 之间的正数，当前请求含非法值——请改成 1.0 这类正数"
                        + "（正确写法示例：1.0）", BizErrorCode.VALIDATION_ERROR);
            }
        }
        return requested;
    }

    /** Bilinear resample into an independent image (the crop subimage shares the frame raster). */
    private static BufferedImage render(BufferedImage source, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = out.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return out;
    }

    private static byte[] toPng(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (Exception e) {
            throw new ServiceException("取图编码 PNG 失败，请重试；持续失败请在「执行监看」改选最新帧再试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return out.toByteArray();
    }

    /** Filename-friendly scale text (1.0 -> "1.0", 1.25 -> "1.25"). */
    private static String formatScale(double scale) {
        return scale == Math.floor(scale) ? String.format("%.1f", scale) : String.valueOf(scale);
    }
}
