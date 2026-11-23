package com.sixbean.testpike.core.executor.api.dto;

import java.util.List;


public record BatchCancelResult(int cancelled, List<Long> rejectedTaskIds) {
}
