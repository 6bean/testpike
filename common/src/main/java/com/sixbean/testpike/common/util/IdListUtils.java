package com.sixbean.testpike.common.util;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.ArrayList;
import java.util.List;


public final class IdListUtils {

    private IdListUtils() {
    }

    
    public static List<Long> parseLongs(String raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(token));
            } catch (NumberFormatException e) {
                throw new ServiceException("项目 ID 必须为数字，实际: " + token,
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        return ids;
    }
}
