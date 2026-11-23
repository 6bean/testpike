package com.sixbean.testpike.core.member.api.controller;

import com.sixbean.testpike.core.member.api.dto.UserProjectInfo;

import com.sixbean.testpike.core.member.domain.service.UserProjectQueryService;


import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/user-projects")
public class UserProjectInternalController {

    private final UserProjectQueryService queryService;

    public UserProjectInternalController(UserProjectQueryService queryService) {
        this.queryService = queryService;
    }

    
    @GetMapping
    public Response<List<UserProjectInfo>> listByUser(@RequestParam long userId,
                                                      @RequestParam(defaultValue = "false") boolean systemManager) {
        return Response.builderSuccess(queryService.findVisibleByUserId(userId, systemManager));
    }
}
