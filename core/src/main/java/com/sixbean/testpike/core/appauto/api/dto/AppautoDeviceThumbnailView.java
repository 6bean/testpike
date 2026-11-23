package com.sixbean.testpike.core.appauto.api.dto;

/**
 * Device-wall thumbnail cell (read face for the wall grid).
 * <p>state semantics: LIVE = a downscaled copy of the newest monitor frame of the
 * running task addressing this device; IDLE = no running task addresses this
 * device; NO_FRAME = a running task exists but the live-frame ring is empty (the
 * feed never started or dropped past its keep-alive window). reason carries the
 * plain wording for IDLE/NO_FRAME so the wall cell can say what is happening.
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

    public static final String STATE_LIVE = "LIVE";
    public static final String STATE_IDLE = "IDLE";
    public static final String STATE_NO_FRAME = "NO_FRAME";

    /** Honest non-live cell: no image, state + reason named. */
    public static AppautoDeviceThumbnailView withoutFrame(long deviceId, Long taskId,
            String state, String reason) {
        return new AppautoDeviceThumbnailView(deviceId, taskId, state, reason,
                null, null, null, null, null);
    }
}
