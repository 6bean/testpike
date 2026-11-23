package com.sixbean.testpike.admin.reportcenter.api.dto;

/**
 * Admin-side mirror of the defect burndown block in the program overview feed
 * (shape parity with core defects api dto DefectBurnWindow — field names ride
 * the JSON contract, the bridge never reshapes).
 *
 * <p>Open caliber: status &lt;&gt; 5; {@code openAtEnd} keeps the invariant
 * start + created - closed so the UI can render the burn line without recomputing.
 */
public record ReportCenterDefectBurnWindowView(long projectId, long openAtStart,
        long createdWithin, long closedWithin, long openAtEnd) {
}
