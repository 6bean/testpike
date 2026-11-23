package com.sixbean.testpike.admin.search.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.search.api.dto.SearchResultsView;
import com.sixbean.testpike.admin.search.infrastructure.remote.client.CoreSearchClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final CoreSearchClient coreSearchClient;
    private final ProjectAccessGuard guard;

    public SearchController(CoreSearchClient coreSearchClient, ProjectAccessGuard guard) {
        this.coreSearchClient = coreSearchClient;
        this.guard = guard;
    }

    
    @GetMapping
    public Response<SearchResultsView> search(@RequestParam(required = false) String keyword) {
        CurrentUser user = UserContext.current();
        boolean allVisible = guard.isSystemManager(user);
        return Response.builderSuccess(coreSearchClient.search(allVisible, keyword));
    }
}
