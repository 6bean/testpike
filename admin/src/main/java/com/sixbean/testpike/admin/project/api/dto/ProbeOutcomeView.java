package com.sixbean.testpike.admin.project.api.dto;

/**
 * Outcome of one "test now" run (login-state probe or credential test):
 * the run token keys the event tail, the verdict wording rides along for
 * the toast. Cross-domain by design - both doors share the shape.
 */
public record ProbeOutcomeView(String runToken, String verdict, String verdictHuman,
                               String grade, String gradeHuman, String note, Integer injections) {

    /** Login-state probe shape: verdict wording only, evidence columns absent. */
    public ProbeOutcomeView(String runToken, String verdict, String verdictHuman) {
        this(runToken, verdict, verdictHuman, null, null, null, null);
    }
}
