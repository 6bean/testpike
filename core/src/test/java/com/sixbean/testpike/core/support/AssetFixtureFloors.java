package com.sixbean.testpike.core.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Watermark pair for the shared-asset fixture suites .
 *
 * <p>{@link #mark} captures the tp_asset / tp_asset_reference / tp_notification
 * id floors before a test runs; {@link #sweep} then deletes ONLY the rows this
 * run created above those floors — child pins first, then the parent asset
 * rows, so the V359 pin FK stays satisfied. The blanket TRUNCATE these suites
 * used to carry rewound shared identity sequences and wiped concurrent
 * suites' rows on the dev database (R37). Single source for the five
 * shared-asset suites (R40).
 */
public final class AssetFixtureFloors {

    private final long assetFloor;
    private final long referenceFloor;
    private final long notificationFloor;

    private AssetFixtureFloors(long assetFloor, long referenceFloor, long notificationFloor) {
        this.assetFloor = assetFloor;
        this.referenceFloor = referenceFloor;
        this.notificationFloor = notificationFloor;
    }

    /** Capture the three asset-domain id floors from the live database. */
    public static AssetFixtureFloors mark(JdbcTemplate jdbc) {
        return new AssetFixtureFloors(
                SharedDbFixtureSweep.maxId(jdbc, "tp_asset"),
                SharedDbFixtureSweep.maxId(jdbc, "tp_asset_reference"),
                SharedDbFixtureSweep.maxId(jdbc, "tp_notification"));
    }

    /** Delete exactly the rows created above the captured floors, child first. */
    public void sweep(JdbcTemplate jdbc) {
        SharedDbFixtureSweep.deleteSince(jdbc, "tp_asset_reference", referenceFloor);
        SharedDbFixtureSweep.deleteSince(jdbc, "tp_asset", assetFloor);
        SharedDbFixtureSweep.deleteSince(jdbc, "tp_notification", notificationFloor);
    }

    /** Floor accessor for window-scoped asset-row assertions. */
    public long assetFloor() {
        return assetFloor;
    }

    /** Floor accessor for window-scoped pin-row assertions. */
    public long referenceFloor() {
        return referenceFloor;
    }

    /** Floor accessor for window-scoped notification-row reads. */
    public long notificationFloor() {
        return notificationFloor;
    }
}
