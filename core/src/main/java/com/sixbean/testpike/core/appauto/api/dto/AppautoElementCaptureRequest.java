package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;

/**
 * Collaborative capture request: cut one rectangle out of a live monitor frame
 * and file the result as an image element profile with scaled variants.
 *
 * @param taskId              live frame channel anchor (the same key the monitor
 *                            viewer reads: appauto-monitor:frames:{taskId})
 * @param frameIndex          0 = newest buffered frame, larger values older;
 *                            null is treated as newest
 * @param name                element display name, unique inside the project
 * @param x                   crop rectangle left edge in frame pixels
 * @param y                   crop rectangle top edge in frame pixels
 * @param width               crop rectangle width in frame pixels
 * @param height              crop rectangle height in frame pixels
 * @param scales              scale factors for the stored variants; empty means
 *                            the server-side default list (hot-updatable key)
 * @param confidenceThreshold template match floor recorded on the profile
 */
public record AppautoElementCaptureRequest(
        long taskId,
        Integer frameIndex,
        String name,
        int x,
        int y,
        int width,
        int height,
        List<Double> scales,
        Double confidenceThreshold) {
}
