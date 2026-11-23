package com.sixbean.testpike.core.executor.api.dto;

import java.util.List;


public record ExecutorTaskPageView(int pending, int running, int done, int failed,
                                   List<ExecTaskInfo> list, long total) {
}
