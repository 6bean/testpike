package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;


public record RerunSource(ExecTaskInfo task, Long batchId) {
}
