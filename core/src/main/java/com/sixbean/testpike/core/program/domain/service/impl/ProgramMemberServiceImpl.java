package com.sixbean.testpike.core.program.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.program.api.dto.ProgramMemberInfo;
import com.sixbean.testpike.core.program.domain.service.ProgramMemberService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Group-level grant persistence with the audit duty on the central channel.
 *
 * <p>Audit shape (aligned with the existing precedent, no side table): object_type
 * {@code program_grant}, object_id = program id, before/after carry the member side of
 * the change as a detached map snapshot.
 */
@Service
public class ProgramMemberServiceImpl implements ProgramMemberService {

    static final String AUDIT_OBJECT_TYPE = "program_grant";

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditLogService auditLogService;

    public ProgramMemberServiceImpl(NamedParameterJdbcTemplate jdbc, AuditLogService auditLogService) {
        this.jdbc = jdbc;
        this.auditLogService = auditLogService;
    }

    @Override
    public void add(long programId, long userId, int role, long operatorId) {
        requireRole(role);
        requireProgram(programId);
        try {
            jdbc.update("INSERT INTO tp_user_program (program_id, user_id, role, created_by) "
                            + "VALUES (:pid, :uid, :role, :operatorId)",
                    new MapSqlParameterSource()
                            .addValue("pid", programId)
                            .addValue("uid", userId)
                            .addValue("role", role)
                            .addValue("operatorId", operatorId));
        } catch (DuplicateKeyException e) {
            throw new ServiceException("This user is already a member of the program",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        audit(operatorId, programId, "grant", null, snapshot(userId, role));
    }

    @Override
    public void updateRole(long programId, long userId, int role, long operatorId) {
        requireRole(role);
        ProgramMemberInfo current = loadRow(programId, userId);
        jdbc.update("UPDATE tp_user_program SET role = :role, updated_at = now() "
                        + "WHERE program_id = :pid AND user_id = :uid",
                new MapSqlParameterSource()
                        .addValue("role", role)
                        .addValue("pid", programId)
                        .addValue("uid", userId));
        audit(operatorId, programId, "change_role",
                snapshot(userId, current.role()), snapshot(userId, role));
    }

    @Override
    public void remove(long programId, long userId, long operatorId) {
        ProgramMemberInfo current = loadRow(programId, userId);
        jdbc.update("DELETE FROM tp_user_program WHERE program_id = :pid AND user_id = :uid",
                new MapSqlParameterSource().addValue("pid", programId).addValue("uid", userId));
        audit(operatorId, programId, "revoke", snapshot(userId, current.role()), null);
    }

    @Override
    public List<ProgramMemberInfo> list(long programId) {
        return jdbc.query("SELECT user_id, role FROM tp_user_program WHERE program_id = :pid "
                        + "ORDER BY user_id",
                new MapSqlParameterSource("pid", programId),
                (rs, n) -> new ProgramMemberInfo(rs.getLong("user_id"), rs.getInt("role")));
    }

    private ProgramMemberInfo loadRow(long programId, long userId) {
        List<ProgramMemberInfo> rows = jdbc.query("SELECT user_id, role FROM tp_user_program "
                        + "WHERE program_id = :pid AND user_id = :uid",
                new MapSqlParameterSource().addValue("pid", programId).addValue("uid", userId),
                (rs, n) -> new ProgramMemberInfo(rs.getLong("user_id"), rs.getInt("role")));
        if (rows.isEmpty()) {
            throw new ServiceException("This user is not a member of the program", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private void requireRole(int role) {
        if (role != 1 && role != 2) {
            throw new ServiceException("Program role must be 1 (member) or 2 (owner)",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private void requireProgram(long programId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM tp_program WHERE id = :id",
                new MapSqlParameterSource("id", programId), Integer.class);
        if (n == null || n == 0) {
            throw new ServiceException("Program does not exist or was deleted", BizErrorCode.NOT_FOUND);
        }
    }

    private static Map<String, Object> snapshot(long userId, int role) {
        return Map.of("user_id", userId, "role", role);
    }

    private void audit(long operatorId, long programId, String action,
                       Map<String, Object> before, Map<String, Object> after) {
        // Programs are global lenses: projectId stays null on the audit row.
        auditLogService.record(new AuditLogInput(operatorId, null, AUDIT_OBJECT_TYPE,
                String.valueOf(programId), action, before, after));
    }
}
