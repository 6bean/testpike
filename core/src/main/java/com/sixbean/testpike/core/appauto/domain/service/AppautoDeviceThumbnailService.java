package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceThumbnailView;

/**
 * Device-wall thumbnail assembly (stage-two wall cell face).
 * <p>Reuses the monitor-frame ring as the single frame source:
 * the newest buffered frame of the running app-auto task addressing this device
 * is downscaled to a wall-sized JPEG and short-cached, so the wall grid can poll
 * every cell at about one frame per second without a second streaming protocol.
 */
public interface AppautoDeviceThumbnailService {

    /**
     * Current thumbnail cell for one device.
     * <p>Never throws for "no live picture": IDLE (no running task on the device)
     * and NO_FRAME (task running but no buffered frame) come back as honest cells
     * with a plain reason. Only an unknown device id is an error.
     */
    AppautoDeviceThumbnailView thumbnail(long deviceId);
}
