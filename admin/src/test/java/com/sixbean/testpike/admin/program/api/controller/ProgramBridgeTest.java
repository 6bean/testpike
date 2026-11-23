package com.sixbean.testpike.admin.program.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.program.api.dto.CreateProgramRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberAddRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberRoleRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberView;
import com.sixbean.testpike.admin.program.api.dto.ProgramView;
import com.sixbean.testpike.admin.program.api.dto.UpdateProgramRequest;
import com.sixbean.testpike.admin.program.infrastructure.remote.client.StubCoreProgramClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Program admin-bridge contract test :
 * ① the controller must forward whole request objects to the core client — never
 * rebuild them field by field (field-loss incident class, same rule as
 * ProjectAttributeBridgeTest);
 * ② JSON field names must align one-to-one with the core internal DTO contract
 * (cross-service deserialization matches by name);
 * ③ the admin views must receive every field core returns (unknown-field drop
 * = missing display data).
 * <p>StubCoreProgramClient is the integration-boundary stand-in; the real
 * admin → core chain is covered by the L3 bridge smoke at segment close.
 */
class ProgramBridgeTest {

    private final StubCoreProgramClient client = new StubCoreProgramClient();
    private final ProgramController controller = new ProgramController(client);
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginAdmin() {
        UserContext.set(new CurrentUser(1L, "admin", "A", "系统管理员", List.of("ADMIN"),
                System.currentTimeMillis() + 3600_000, null));
    }

    @Test
    void createPassesWholeRequestObjectToCore() {
        loginAdmin();
        client.createdId(42L);
        CreateProgramRequest request = new CreateProgramRequest("中台集成组", "订单+库存+主数据");
        var response = controller.create(request);
        assertSame(request, client.lastCreateRequest(),
                "admin bridge must forward the whole object, no per-field copy");
        assertEquals(42L, response.getData());
    }

    @Test
    void updatePassesIdAndWholeRequestObjectToCore() {
        loginAdmin();
        UpdateProgramRequest request = new UpdateProgramRequest("改名后", "新描述");
        controller.update(7L, request);
        assertEquals(7L, client.lastUpdateId());
        assertSame(request, client.lastUpdateRequest(),
                "PUT must be whole-object pass-through toward core ProgramUpdateInput");
    }

    @Test
    void stateAndDeleteForwardPathAndQueryParam() {
        loginAdmin();
        controller.setState(3L, 0);
        assertEquals(3L, client.lastStateId());
        assertEquals(0, client.lastState());
        controller.delete(9L);
        assertEquals(9L, client.lastDeletedId());
    }

    @Test
    void attachAndDetachForwardBothPathIds() {
        loginAdmin();
        controller.attachProject(2L, 55L);
        assertEquals(2L, client.lastAttachedProgramId());
        assertEquals(55L, client.lastAttachedProjectId());
        controller.detachProject(2L, 55L);
        assertEquals(2L, client.lastDetachedProgramId());
        assertEquals(55L, client.lastDetachedProjectId());
    }

    @Test
    void memberGrantAndRoleChangePassWholeRequestObjects() {
        loginAdmin();
        ProgramMemberAddRequest grant = new ProgramMemberAddRequest(8L, 2);
        controller.addMember(4L, grant);
        assertEquals(4L, client.lastMemberProgramId());
        assertSame(grant, client.lastMemberGrant(),
                "group grant must be whole-object pass-through toward core ProgramMemberGrantInput");

        ProgramMemberRoleRequest roleChange = new ProgramMemberRoleRequest(1);
        controller.updateMemberRole(4L, 8L, roleChange);
        assertEquals(4L, client.lastRoleProgramId());
        assertEquals(8L, client.lastRoleUserId());
        assertSame(roleChange, client.lastMemberRole());

        controller.removeMember(4L, 8L);
        assertEquals(4L, client.lastRemovedProgramId());
        assertEquals(8L, client.lastRemovedUserId());
    }

    @Test
    void readEndpointsReturnCoreViewsAndProjectIds() {
        loginAdmin();
        ProgramView view = new ProgramView(1L, "P", "d", 1, "2026-10-31 10:00:00");
        client.programView(view);
        assertSame(view, controller.get(1L).getData(), "single view must pass through");
        List<ProgramView> views = List.of(view);
        client.programList(views);
        assertEquals(views, controller.list().getData());
        client.projectIds(List.of(11L, 12L));
        assertEquals(List.of(11L, 12L), controller.listProjectIds(1L).getData());
        List<ProgramMemberView> members = List.of(new ProgramMemberView(8L, 2));
        client.members(members);
        assertEquals(members, controller.listMembers(1L).getData());
    }

    @Test
    void requestJsonFieldNamesAlignWithCoreContract() throws Exception {
        Map<?, ?> create = mapper.readValue(
                mapper.writeValueAsString(new CreateProgramRequest("n", "d")), Map.class);
        assertTrue(create.containsKey("name"), "CreateProgramRequest must carry name");
        assertTrue(create.containsKey("description"), "CreateProgramRequest must carry description");

        Map<?, ?> update = mapper.readValue(
                mapper.writeValueAsString(new UpdateProgramRequest("n", "d")), Map.class);
        assertEquals(List.of("name", "description"), update.keySet().stream().toList());

        Map<?, ?> grant = mapper.readValue(
                mapper.writeValueAsString(new ProgramMemberAddRequest(8L, 1)), Map.class);
        assertEquals(List.of("userId", "role"), grant.keySet().stream().toList());

        Map<?, ?> role = mapper.readValue(
                mapper.writeValueAsString(new ProgramMemberRoleRequest(2)), Map.class);
        assertEquals(List.of("role"), role.keySet().stream().toList());
    }

    @Test
    void viewsReceiveEveryCoreInfoField() throws Exception {
        ProgramView view = mapper.readValue(
                "{\"id\":5,\"name\":\"P\",\"description\":\"d\",\"state\":1,"
                        + "\"createdAt\":\"2026-10-31 09:00:00\"}", ProgramView.class);
        assertEquals(5L, view.id());
        assertEquals("P", view.name());
        assertEquals("d", view.description());
        assertEquals(1, view.state());
        assertEquals("2026-10-31 09:00:00", view.createdAt());

        ProgramMemberView member = mapper.readValue(
                "{\"userId\":8,\"role\":2}", ProgramMemberView.class);
        assertEquals(8L, member.userId());
        assertEquals(2, member.role());
    }
}
