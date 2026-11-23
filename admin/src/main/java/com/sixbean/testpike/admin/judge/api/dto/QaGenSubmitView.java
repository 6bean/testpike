package com.sixbean.testpike.admin.judge.api.dto;

/**
 * Submit receipt mirrored from ai-service QaGenSubmitView (question-generation
 * job plane, admin bridge). The door answers in exactly one of two
 * shapes: {@code sync} carries the full inline preview, {@code accepted}
 * carries the run token of a background job the caller then polls through
 * the qa-gen-job door. The bridge passes the shape through untouched — no
 * second decision point on the admin side.
 *
 * @param mode sync / accepted (closed set, mirrors the ai-service constants)
 * @param runToken job receipt, null in sync mode
 * @param preview inline preview, null in accepted mode
 */
public record QaGenSubmitView(String mode, String runToken, QaGenPreviewView preview) {

    /** Group count inside the sync cap: full preview answered inline. */
    public static final String MODE_SYNC = "sync";

    /** Over the sync cap: run accepted, caller polls the job face. */
    public static final String MODE_ACCEPTED = "accepted";

    /** Group count inside the sync cap: full preview answered inline. */
    public static QaGenSubmitView sync(QaGenPreviewView preview) {
        return new QaGenSubmitView(MODE_SYNC, null, preview);
    }

    /** Beyond the sync cap: run token receipt, no preview carried. */
    public static QaGenSubmitView accepted(String runToken) {
        return new QaGenSubmitView(MODE_ACCEPTED, runToken, null);
    }
}
