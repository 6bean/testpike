package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;

/**
 * Collaborative capture payload bridged to the core element capture API
 * (mirror of the core record, same field names for straight mapping): cut one
 * rectangle out of a live monitor frame and file it as an image element.
 *
 * @param taskId              live frame channel anchor (same key the monitor
 *                            viewer reads)
 * @param frameIndex          0 = newest buffered frame, larger values older;
 *                            null means newest
 * @param name                element display name, unique inside the project
 * @param x                   crop rectangle left edge in frame pixels
 * @param y                   crop rectangle top edge in frame pixels
 * @param width               crop rectangle width in frame pixels
 * @param height              crop rectangle height in frame pixels
 * @param scales              scale factors for stored variants; empty falls
 *                            back to the server-side default list
 * @param confidenceThreshold template match floor recorded on the profile
 */
public record AppautoElementCaptureInput(
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
