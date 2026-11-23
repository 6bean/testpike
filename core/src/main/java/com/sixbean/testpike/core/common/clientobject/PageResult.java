package com.sixbean.testpike.core.common.clientobject;

import java.util.List;

/**
 * General pagination result: list endpoints return {list, total}, with page/size defaulting to 20 and capped at 200.
 *
 * @param <T> the row data type
 */
public record PageResult<T>(List<T> list, long total) {
}
