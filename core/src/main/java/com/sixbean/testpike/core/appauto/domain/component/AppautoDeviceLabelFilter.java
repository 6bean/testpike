package com.sixbean.testpike.core.appauto.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single source for the device-label containment judgement ("this device carries that label").
 *
 * <p>Two surfaces consume it: the device list endpoint filter
 * ({@code AppautoDeviceServiceImpl.list}) and the matrix fan-out label-group narrowing
 * ({@code AppautoMatrixDeviceResolver}). One predicate shape and one serialisation path keep
 * the two from drifting apart, and both ride the GIN index ix_appauto_device_labels.
 */
@Component
public class AppautoDeviceLabelFilter {

    /** Bind name the containment predicate expects on the caller's parameter source. */
    private static final String PARAM_NAME = "deviceLabel";

    private final ObjectMapper objectMapper;

    public AppautoDeviceLabelFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * SQL fragment restricting rows to devices carrying one label. The caller must bind
     * {@link #paramName()} to {@link #containsValue(String)}; whitespace-only labels are the
     * caller's decision to treat as unfiltered, this fragment always narrows.
     */
    public String containmentPredicate() {
        return " AND labels @> CAST(:" + PARAM_NAME + " AS jsonb)";
    }

    /** The named parameter the predicate fragment reads. */
    public String paramName() {
        return PARAM_NAME;
    }

    /**
     * Containment value for a single label: a one-element JSON array built by the Jackson
     * serializer, so quotes and backslashes inside a label escape correctly (hand-assembled
     * JSON literals are forbidden).
     */
    public String containsValue(String label) {
        try {
            return objectMapper.writeValueAsString(List.of(label.trim()));
        } catch (JsonProcessingException e) {
            throw new ServiceException("设备标签无法解析为匹配条件（标签 " + label
                    + "）——请改用不含引号与反斜杠的普通文字标签（示例：ios-真机）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
