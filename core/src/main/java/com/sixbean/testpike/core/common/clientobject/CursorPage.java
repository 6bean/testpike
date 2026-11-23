package com.sixbean.testpike.core.common.clientobject;

import java.util.List;

/**
 * Generic cursor pagination result (cursor pilot: keyset pagination does not use count(*)).
 * The {@code nextCursor} is the cursor of the last row on this page (null when hasMore=false, serving as the termination signal).
 * The {@code hasMore} flag is determined by fetching one extra row as a probe, matching the frontend "load more" semantics.
 * <p>The regular page-number pagination contract ({@link PageResult} {list, total}) remains unchanged, and the cursor endpoint is a pilot increment.
 *
 * @param <T> the row data type
 */
public record CursorPage<T>(List<T> list, Long nextCursor, boolean hasMore) {
}
