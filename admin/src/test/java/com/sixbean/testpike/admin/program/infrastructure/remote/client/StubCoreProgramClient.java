package com.sixbean.testpike.admin.program.infrastructure.remote.client;

import com.sixbean.testpike.admin.program.api.dto.CreateProgramRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberAddRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberRoleRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberView;
import com.sixbean.testpike.admin.program.api.dto.ProgramView;
import com.sixbean.testpike.admin.program.api.dto.UpdateProgramRequest;
import com.sixbean.testpike.admin.program.api.dto.UserProgramView;

import java.util.ArrayList;
import java.util.List;

/**
 * Integration-boundary stand-in for {@link CoreProgramClient} (admin unit tests).
 * The real chain is covered by L3 bridge smoke + frontend E2E, same split as
 * {@code StubCoreProjectClient}. Records every call so the contract test can assert
 * whole-object pass-through (identity, not a field copy).
 */
public class StubCoreProgramClient implements CoreProgramClient {

    private final List<String> calls = new ArrayList<>();

    private CreateProgramRequest lastCreateRequest;
    private UpdateProgramRequest lastUpdateRequest;
    private ProgramMemberAddRequest lastMemberGrant;
    private ProgramMemberRoleRequest lastMemberRole;
    private long createdId = 1L;
    private ProgramView programView = new ProgramView(1L, "P", "d", 1, "2026-10-31 00:00:00");
    private List<ProgramView> programList = List.of();
    private List<Long> projectIds = List.of();
    private List<ProgramMemberView> members = List.of();

    @Override
    public long createProgram(CreateProgramRequest request) {
        this.lastCreateRequest = request;
        calls.add("create");
        return createdId;
    }

    @Override
    public void updateProgram(long id, UpdateProgramRequest request) {
        this.lastUpdateId = id;
        this.lastUpdateRequest = request;
        calls.add("update:" + id);
    }

    private long lastUpdateId;
    private long lastStateId;
    private int lastState;
    private long lastDeletedId;
    private long lastAttachedProgramId;
    private long lastAttachedProjectId;
    private long lastDetachedProgramId;
    private long lastDetachedProjectId;
    private long lastMemberProgramId;
    private long lastRoleProgramId;
    private long lastRoleUserId;
    private long lastRemovedProgramId;
    private long lastRemovedUserId;

    @Override
    public void setProgramState(long id, int state) {
        this.lastStateId = id;
        this.lastState = state;
        calls.add("state:" + id + ":" + state);
    }

    @Override
    public void deleteProgram(long id) {
        this.lastDeletedId = id;
        calls.add("delete:" + id);
    }

    @Override
    public ProgramView getProgram(long id) {
        calls.add("get:" + id);
        return programView;
    }

    @Override
    public List<ProgramView> listPrograms() {
        calls.add("list");
        return programList;
    }

    @Override
    public List<UserProgramView> listProgramsForUser(long userId, boolean systemManager) {
        this.lastProgramsUserId = userId;
        this.lastProgramsSystemManager = systemManager;
        calls.add("programsForUser:" + userId + ":" + systemManager);
        return userPrograms;
    }

    private Long lastProgramsUserId;
    private Boolean lastProgramsSystemManager;
    private List<UserProgramView> userPrograms = List.of();

    @Override
    public void attachProject(long id, long projectId) {
        this.lastAttachedProgramId = id;
        this.lastAttachedProjectId = projectId;
        calls.add("attach:" + id + ":" + projectId);
    }

    @Override
    public void detachProject(long id, long projectId) {
        this.lastDetachedProgramId = id;
        this.lastDetachedProjectId = projectId;
        calls.add("detach:" + id + ":" + projectId);
    }

    @Override
    public List<Long> listProgramProjectIds(long id) {
        calls.add("projectIds:" + id);
        return projectIds;
    }

    @Override
    public void addMember(long id, ProgramMemberAddRequest request) {
        this.lastMemberProgramId = id;
        this.lastMemberGrant = request;
        calls.add("addMember:" + id);
    }

    @Override
    public void updateMemberRole(long id, long userId, ProgramMemberRoleRequest request) {
        this.lastRoleProgramId = id;
        this.lastRoleUserId = userId;
        this.lastMemberRole = request;
        calls.add("memberRole:" + id + ":" + userId);
    }

    @Override
    public void removeMember(long id, long userId) {
        this.lastRemovedProgramId = id;
        this.lastRemovedUserId = userId;
        calls.add("removeMember:" + id + ":" + userId);
    }

    @Override
    public List<ProgramMemberView> listMembers(long id) {
        calls.add("members:" + id);
        return members;
    }

    // ── test knobs ────────────────────────────────────────────────────────────

    public void createdId(long id) {
        this.createdId = id;
    }

    public void programView(ProgramView view) {
        this.programView = view;
    }

    public void programList(List<ProgramView> views) {
        this.programList = views;
    }

    public void userPrograms(List<UserProgramView> views) {
        this.userPrograms = views;
    }

    public Long lastProgramsUserId() {
        return lastProgramsUserId;
    }

    public Boolean lastProgramsSystemManager() {
        return lastProgramsSystemManager;
    }

    public void projectIds(List<Long> ids) {
        this.projectIds = ids;
    }

    public void members(List<ProgramMemberView> grantRows) {
        this.members = grantRows;
    }

    public List<String> calls() {
        return calls;
    }

    public CreateProgramRequest lastCreateRequest() {
        return lastCreateRequest;
    }

    public long lastUpdateId() {
        return lastUpdateId;
    }

    public UpdateProgramRequest lastUpdateRequest() {
        return lastUpdateRequest;
    }

    public long lastStateId() {
        return lastStateId;
    }

    public int lastState() {
        return lastState;
    }

    public long lastDeletedId() {
        return lastDeletedId;
    }

    public long lastAttachedProgramId() {
        return lastAttachedProgramId;
    }

    public long lastAttachedProjectId() {
        return lastAttachedProjectId;
    }

    public long lastDetachedProgramId() {
        return lastDetachedProgramId;
    }

    public long lastDetachedProjectId() {
        return lastDetachedProjectId;
    }

    public long lastMemberProgramId() {
        return lastMemberProgramId;
    }

    public ProgramMemberAddRequest lastMemberGrant() {
        return lastMemberGrant;
    }

    public long lastRoleProgramId() {
        return lastRoleProgramId;
    }

    public long lastRoleUserId() {
        return lastRoleUserId;
    }

    public ProgramMemberRoleRequest lastMemberRole() {
        return lastMemberRole;
    }

    public long lastRemovedProgramId() {
        return lastRemovedProgramId;
    }

    public long lastRemovedUserId() {
        return lastRemovedUserId;
    }
}
