package com.sixbean.testpike.admin.reportcenter.api.dto;

/**
 * Caliber registry footnote of the program overview feed (mirror of the core
 * record; code/displayName/version read straight from the registry so the UI
 * footnote cannot drift from what the server evaluated).
 */
public record ReportCenterCaliberFootnoteView(String code, String displayName, String version) {
}
