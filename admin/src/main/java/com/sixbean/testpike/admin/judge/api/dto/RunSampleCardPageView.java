package com.sixbean.testpike.admin.judge.api.dto;

import java.util.List;


public record RunSampleCardPageView(Long runId, int page, int size, long total,
                                    List<RunSampleCardView> cards) {
}
