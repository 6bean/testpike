package com.sixbean.testpike.core.dbconnection.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.dbconnection.api.dto.ConnectivityTestInfo;
import com.sixbean.testpike.core.dbconnection.api.dto.DbConnectionInfo;
import com.sixbean.testpike.core.dbconnection.api.dto.DbConnectionInput;
import com.sixbean.testpike.core.dbconnection.domain.service.DbConnectionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/projects/{projectId}/env-connections")
public class DbConnectionInternalController {

    private final DbConnectionService connectionService;

    public DbConnectionInternalController(DbConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @GetMapping
    public Response<List<DbConnectionInfo>> list(@PathVariable long projectId) {
        return Response.builderSuccess(connectionService.list(projectId));
    }

    @PostMapping
    public Response<Long> create(@PathVariable long projectId, @RequestBody DbConnectionInput input) {
        return Response.builderSuccess(connectionService.create(projectId, input));
    }

    @PutMapping("/{id}")
    public Response<DbConnectionInfo> update(@PathVariable long projectId, @PathVariable long id,
                                             @RequestBody DbConnectionInput input) {
        return Response.builderSuccess(connectionService.update(projectId, id, input));
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long projectId, @PathVariable long id) {
        connectionService.delete(projectId, id);
        return Response.builderSuccess();
    }

    
    @PostMapping("/{id}/test")
    public Response<ConnectivityTestInfo> test(@PathVariable long projectId, @PathVariable long id) {
        return Response.builderSuccess(connectionService.test(projectId, id));
    }
}
