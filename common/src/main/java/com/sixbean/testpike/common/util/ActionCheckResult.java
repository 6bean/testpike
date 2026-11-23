package com.sixbean.testpike.common.util;

import java.util.List;


public record ActionCheckResult(boolean passed, List<String> errors) {
}
