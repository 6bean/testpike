package com.sixbean.testpike.core.program.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.program.api.dto.ProgramInfo;
import com.sixbean.testpike.core.program.api.dto.UserProgramInfo;
import com.sixbean.testpike.core.program.domain.service.ProgramService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Program lens persistence (three annotation tables, no business data ownership).
 *
 * <p>segment 1 scope note: group-grant mutations carry the audit duty in
 * {@link ProgramMemberServiceImpl}; program CRUD itself is not audited until the admin
 * bridge lands the operator context end to end.
 */
@Service
public class ProgramServiceImpl implements ProgramService {

    private final NamedParameterJdbcTemplate jdbc;

    public ProgramServiceImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long create(String name, String description, long operatorId) {
        if (name == null || name.isBlank()) {
            throw new ServiceException("Program name is required", BizErrorCode.VALIDATION_ERROR);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update("INSERT INTO tp_program (name, description, state, created_by) "
                            + "VALUES (:name, :description, 1, :operatorId)",
                    new MapSqlParameterSource()
                            .addValue("name", name.trim())
                            .addValue("description", description)
                            .addValue("operatorId", operatorId),
                    keyHolder, new String[]{"id"});
        } catch (DuplicateKeyException e) {
            throw new ServiceException("A program with this name already exists: " + name,
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return keyHolder.getKey().longValue();
    }

    @Override
    public void update(long programId, String name, String description, long operatorId) {
        requireExists(programId);
        int rows = jdbc.update("UPDATE tp_program SET name = :name, description = :description, "
                        + "updated_at = now() WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("name", name == null ? null : name.trim())
                        .addValue("description", description)
                        .addValue("id", programId));
        if (rows == 0) {
            throw new ServiceException("Program no longer exists", BizErrorCode.NOT_FOUND);
        }
    }

    @Override
    public void setState(long programId, int state, long operatorId) {
        if (state != 0 && state != 1) {
            throw new ServiceException("Program state must be 1 (active) or 0 (archived)",
                    BizErrorCode.VALIDATION_ERROR);
        }
        requireExists(programId);
        jdbc.update("UPDATE tp_program SET state = :state, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("state", state).addValue("id", programId));
    }

    @Override
    @Transactional
    public void delete(long programId, long operatorId) {
        requireExists(programId);
        // Journey plans keep a foreign key onto the program: refuse with the
        // conflict code and the blocking count instead of leaking the raw
        // constraint violation as a generic 500 the user cannot act on.
        Integer referencedJourneys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_journey WHERE program_id = :pid",
                new MapSqlParameterSource("pid", programId), Integer.class);
        if (referencedJourneys != null && referencedJourneys > 0) {
            throw new ServiceException("This program still has " + referencedJourneys
                    + " journey plan(s) referencing it; delete those journeys first",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        // The lens holds nothing else: dropping it only removes membership and grant rows.
        jdbc.update("DELETE FROM tp_user_program WHERE program_id = :pid",
                new MapSqlParameterSource("pid", programId));
        jdbc.update("DELETE FROM tp_program_project WHERE program_id = :pid",
                new MapSqlParameterSource("pid", programId));
        jdbc.update("DELETE FROM tp_program WHERE id = :id", new MapSqlParameterSource("id", programId));
    }

    @Override
    public ProgramInfo get(long programId) {
        List<ProgramInfo> rows = jdbc.query("SELECT id, name, description, state, created_at "
                        + "FROM tp_program WHERE id = :id",
                new MapSqlParameterSource("id", programId),
                (rs, n) -> new ProgramInfo(rs.getLong("id"), rs.getString("name"),
                        rs.getString("description"), rs.getInt("state"),
                        ViewFormats.formatTs(rs.getTimestamp("created_at"))));
        if (rows.isEmpty()) {
            throw new ServiceException("Program does not exist or was deleted", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public List<ProgramInfo> listAll() {
        return jdbc.query("SELECT id, name, description, state, created_at FROM tp_program "
                        + "ORDER BY id",
                new MapSqlParameterSource(),
                (rs, n) -> new ProgramInfo(rs.getLong("id"), rs.getString("name"),
                        rs.getString("description"), rs.getInt("state"),
                        ViewFormats.formatTs((Timestamp) rs.getObject("created_at", java.sql.Timestamp.class))));
    }

    @Override
    public void attachProject(long programId, long projectId, long operatorId) {
        requireExists(programId);
        Integer project = jdbc.queryForObject("SELECT count(*) FROM tp_project WHERE id = :id",
                new MapSqlParameterSource("id", projectId), Integer.class);
        if (project == null || project == 0) {
            throw new ServiceException("Project does not exist: " + projectId, BizErrorCode.NOT_FOUND);
        }
        try {
            jdbc.update("INSERT INTO tp_program_project (program_id, project_id, created_by) "
                            + "VALUES (:pid, :proj, :operatorId)",
                    new MapSqlParameterSource()
                            .addValue("pid", programId)
                            .addValue("proj", projectId)
                            .addValue("operatorId", operatorId));
        } catch (DuplicateKeyException e) {
            throw new ServiceException("This project is already in the program",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Override
    public void detachProject(long programId, long projectId, long operatorId) {
        jdbc.update("DELETE FROM tp_program_project WHERE program_id = :pid AND project_id = :proj",
                new MapSqlParameterSource().addValue("pid", programId).addValue("proj", projectId));
    }

    @Override
    public List<Long> listProjectIds(long programId) {
        requireExists(programId);
        return jdbc.queryForList("SELECT project_id FROM tp_program_project WHERE program_id = :pid "
                        + "ORDER BY project_id",
                new MapSqlParameterSource("pid", programId), Long.class);
    }

    @Override
    public List<UserProgramInfo> listForUser(long userId, boolean systemManager) {
        // Managers get the full active lens; normal users only the programs they belong to.
        String programSql = systemManager
                ? "SELECT id, name FROM tp_program WHERE state = 1 ORDER BY id"
                : "SELECT p.id, p.name FROM tp_program p "
                        + "JOIN tp_user_program up ON up.program_id = p.id "
                        + "WHERE up.user_id = :uid AND p.state = 1 ORDER BY p.id";
        MapSqlParameterSource params = systemManager
                ? new MapSqlParameterSource()
                : new MapSqlParameterSource("uid", userId);
        List<UserProgramInfo> shells = jdbc.query(programSql, params,
                (rs, n) -> new UserProgramInfo(rs.getLong("id"), rs.getString("name"), List.of()));
        if (shells.isEmpty()) {
            return shells;
        }
        List<Long> programIds = shells.stream().map(UserProgramInfo::programId).toList();
        Map<Long, List<Long>> projectsByProgram = new LinkedHashMap<>();
        jdbc.query("SELECT program_id, project_id FROM tp_program_project WHERE program_id IN (:ids) "
                        + "ORDER BY program_id, project_id",
                new MapSqlParameterSource("ids", programIds),
                (rs, n) -> {
                    projectsByProgram.computeIfAbsent(rs.getLong("program_id"),
                            k -> new ArrayList<>()).add(rs.getLong("project_id"));
                    return null;
                });
        return shells.stream()
                .map(p -> new UserProgramInfo(p.programId(), p.name(),
                        List.copyOf(projectsByProgram.getOrDefault(p.programId(), List.of()))))
                .toList();
    }

    private void requireExists(long programId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM tp_program WHERE id = :id",
                new MapSqlParameterSource("id", programId), Integer.class);
        if (n == null || n == 0) {
            throw new ServiceException("Program does not exist or was deleted", BizErrorCode.NOT_FOUND);
        }
    }
}
