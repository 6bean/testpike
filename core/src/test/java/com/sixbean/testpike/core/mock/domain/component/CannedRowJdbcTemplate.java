package com.sixbean.testpike.core.mock.domain.component;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * jdbc stand-in for the binding-gate wiring test: answers the gate's single
 * tp_mock_space lookup from canned values and never opens a connection. The
 * parent jdbc is built over a stub datasource only to satisfy the constructor
 * — no real query ever runs through it.
 */
class CannedRowJdbcTemplate extends NamedParameterJdbcTemplate {

    private final Long ownerId;
    private final Integer status;

    CannedRowJdbcTemplate(Long ownerId, Integer status) {
        super(new javax.sql.DataSource() {
            @Override
            public java.sql.Connection getConnection() {
                throw new UnsupportedOperationException("canned rows only, no real connection");
            }

            @Override
            public java.sql.Connection getConnection(String username, String password) {
                throw new UnsupportedOperationException("canned rows only, no real connection");
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                throw new UnsupportedOperationException("not a proxy");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        });
        this.ownerId = ownerId;
        this.status = status;
    }

    @Override
    public List<Map<String, Object>> queryForList(String sql, SqlParameterSource params) {
        if (ownerId == null) {
            return List.of();
        }
        Map<String, Object> row = new HashMap<>();
        row.put("project_id", ownerId);
        row.put("status", status);
        return List.of(row);
    }
}
