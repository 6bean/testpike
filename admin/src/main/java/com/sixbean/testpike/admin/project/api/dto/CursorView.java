package com.sixbean.testpike.admin.project.api.dto;

import java.util.List;


public record CursorView<T>(List<T> list, Long nextCursor, boolean hasMore) {
}
