package com.sixbean.testpike.admin.appauto.api.dto;

/**
 * Mirror of the core device-wall thumbnail cell (admin bridge read face).
 * <p>Field order equals the core view so the JSON contract stays single-shaped;
 * state semantics: LIVE = downscaled newest monitor frame of the running task on
 * this device, IDLE = no running task addresses it, NO_FRAME = task running but
 * the live ring is empty. reason carries the plain wording for non-live cells.
 */
public record AppautoDeviceThumbnailView(
        long deviceId,
        Long taskId,
        String state,
        String reason,
        String imageBase64,
        Integer imageWidth,
        Integer imageHeight,
        Integer sourceWidth,
        Integer sourceHeight) {

    /** Honest non-live cell shape (mirrors the core factory). */
    public static AppautoDeviceThumbnailView withoutFrame(long deviceId, Long taskId,
            String state, String reason) {
        return new AppautoDeviceThumbnailView(deviceId, taskId, state, reason,
                null, null, null, null, null);
    }
}
