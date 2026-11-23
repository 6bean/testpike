package com.sixbean.testpike.admin.spec.api.dto;

import java.util.List;

/**
 * One endpoint-level change row of a contract version diff (mirrors core
 * ApiSpecChangeItem; impact lists already closed in core — no re-join here).
 *
 * @param endpointKey contract endpoint key (upper METHOD + normalized path)
 * @param changeType  add | changed | removed
 */
public record ApiSpecChangeItemView(String endpointKey, String changeType,
                                    List<Long> requestIds, List<Long> caseIds,
                                    List<Long> mockStubIds) {
}
