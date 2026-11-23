package com.sixbean.testpike.admin.usage.api.dto;

import java.util.List;


public record UsageSamplePageView(List<UsageSampleView> list, long total) {
}
