package com.sixbean.testpike.aiservice.judge.api.dto;

/**
 * Submit receipt of the question-generation door .
 * The door answers in exactly one of two shapes: {@code sync} keeps the old
 * all-at-once preview (only while the sealed group count stays inside
 * judge.qa-gen.sync-group-max), {@code accepted} hands back the run token of
 * a background job whose progress rides {@link QaGenJobView} and the shared
 * process-event window. The two faces are mutually exclusive by construction
 * — an accepted receipt never carries a preview, a sync answer never carries
 * a run token.
 */
public record QaGenSubmitView(String mode, String runToken, QaGenPreviewView preview) {

    /** Group count inside the sync cap: full preview answered inline. */
    public static final String MODE_SYNC = "sync";

    /** Over the sync cap: run accepted, caller polls the job face. */
    public static final String MODE_ACCEPTED = "accepted";

    public static QaGenSubmitView sync(QaGenPreviewView preview) {
        return new QaGenSubmitView(MODE_SYNC, null, preview);
    }

    public static QaGenSubmitView accepted(String runToken) {
        return new QaGenSubmitView(MODE_ACCEPTED, runToken, null);
    }
}
