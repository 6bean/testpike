package com.sixbean.testpike.core.common.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;

/**
 * The pagination query strategy is a cross-domain common component and the single source of truth for semantics.
 * <p>For normal list interfaces, the requested size is forcibly limited to at most {@link #MAX_REQUEST_SIZE}.
 * Requests exceeding the limit are rejected with a 40001 human-readable message, not silently clamped, to prevent the frontend from showing a false full page.
 * <p>The virtual scrolling exception, with verified zero contract changes, applies to whitelisted endpoints.
 * The single code enum {@link VirtualScrollEndpoint} retains silent clamping to [1, {@link #MAX_VIRTUAL_SIZE}].
 * Both paths unify the lower bound by normalizing values ≤0 to 1.
 *
 */
public final class PageQueryPolicy {

    /** The normal pagination size upper limit rejects exceeded values with a 40001 error. */
    public static final int MAX_REQUEST_SIZE = 200;

 /** The virtual scrolling fetch size upper limit supports fetching more than 1000 rows at once and retains clamping semantics. */
    public static final int MAX_VIRTUAL_SIZE = 1000;

    private PageQueryPolicy() {
    }

    /**
     * For normal interfaces, forcibly validate the page size by normalizing values ≤0 to 1 and rejecting values exceeding {@link #MAX_REQUEST_SIZE}.
     *
     * @param size The requested page size.
     * @return A safe size in the range [1, 200].
     * @throws ValidationException If the size exceeds the upper limit, with a 40001 human-readable message.
     */
    public static int enforceSize(int size) {
        if (size <= 0) {
            return 1;
        }
        if (size > MAX_REQUEST_SIZE) {
            throw new ValidationException(
                    "单页最大 " + MAX_REQUEST_SIZE + " 条，当前请求 " + size + " 条，请缩小分页大小");
        }
        return size;
    }

    /**
 * Clamp the page size for whitelisted virtual scrolling endpoints, with a lower bound of 1.
     * The upper bound is {@link #MAX_VIRTUAL_SIZE}, and values exceeding it are silently clamped without errors so the frontend can shard data using the clamped total.
     *
     * @param endpoint The whitelisted endpoint, explicitly registered with compile-time enforcement; see {@link VirtualScrollEndpoint}.
     * @param size The requested page size, which may be 0, negative, or exceed the limit.
     * @return A safe size in the range [1, 1000].
     */
    public static int clampSize(VirtualScrollEndpoint endpoint, int size) {
        return Math.min(Math.max(size, 1), MAX_VIRTUAL_SIZE);
    }

    /**
 * Clamp the offset to a lower bound of 0 and an upper bound of maxOffset for deep pagination protection.
     *
     * @param page The page number, starting from 1.
     * @param size The validated page size.
     * @param maxOffset The upper bound for the offset.
     * @return A safe offset in the range [0, maxOffset].
     */
    public static long clampOffset(int page, int size, int maxOffset) {
        return Math.min((long) Math.max(page - 1, 0) * size, maxOffset);
    }
}
