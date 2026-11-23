package com.sixbean.testpike.admin.judge.api.dto;

import java.util.List;

/**
 * Evaluation-run history page (AW4; mirrors core JudgeRunPageView — the
 * size bound 1..200 and every filter decision stay single-source in core).
 *
 * @param page  1-based page number (echoed)
 * @param size  page size (echoed)
 * @param total total matching rows
 * @param runs  page rows, newest first
 */
public record JudgeRunPageView(int page, int size, long total, List<JudgeRunView> runs) {
}
