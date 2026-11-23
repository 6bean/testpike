package com.sixbean.testpike.core.usage.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.usage.api.dto.UsageCalibrationInfo;
import com.sixbean.testpike.core.usage.api.dto.UsageSampleInput;
import com.sixbean.testpike.core.usage.api.dto.UsageSampleInfo;

import java.util.List;


public interface UsageCalibrationService {

    
    void record(UsageSampleInput input);

    
    List<UsageCalibrationInfo> calibration(int days);

    
    PageResult<UsageSampleInfo> detailPage(String model, String chain, String keyword,
                                           int page, int size);

    /**
     * Detail page query with an optional server-side sort pair: a null/blank key
     * keeps the historical id DESC order, a whitelisted key orders the whole
     * window by that physical column with a constant id DESC tie-break so paging
     * stays deterministic under equal values, an off-list key is refused
     * fail-closed by the shared sort-column gate.
     * <p>The 5-argument overload above delegates with nulls (zero drift).
     */
    PageResult<UsageSampleInfo> detailPage(String model, String chain, String keyword,
                                           int page, int size, String sortBy, String sortDir);
}
