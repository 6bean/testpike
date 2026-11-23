package com.sixbean.testpike.core.audit.domain.service;

import java.util.ArrayList;
import java.util.List;


record AuditLogFilter(String sql, List<Object> args) {

    static AuditLogFilter of() {
        return new AuditLogFilter(" WHERE 1=1", List.of());
    }

    
    AuditLogFilter withIfPresent(String clause, Object value) {
        if (value == null) {
            return this;
        }
        return with(clause, value);
    }

    
    AuditLogFilter with(String clause, Object value) {
        List<Object> merged = new ArrayList<>(args);
        merged.add(value);
        return new AuditLogFilter(sql + " AND " + clause, List.copyOf(merged));
    }

    
    Object[] withLimit(int limit) {
        List<Object> params = new ArrayList<>(args);
        params.add(limit);
        return params.toArray();
    }

    
    Object[] withLimitOffset(int limit, int offset) {
        List<Object> params = new ArrayList<>(args);
        params.add(limit);
        params.add(offset);
        return params.toArray();
    }

    
    Object[] argsArray() {
        return args.toArray();
    }
}
