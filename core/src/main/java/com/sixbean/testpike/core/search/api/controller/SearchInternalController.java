package com.sixbean.testpike.core.search.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.search.api.dto.SearchResults;
import com.sixbean.testpike.core.search.domain.service.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/search")
public class SearchInternalController {

    private final SearchService searchService;

    public SearchInternalController(SearchService searchService) {
        this.searchService = searchService;
    }

    
    @GetMapping
    public Response<SearchResults> search(@RequestParam long userId,
                                          @RequestParam(defaultValue = "false") boolean allVisible,
                                          @RequestParam(required = false) String keyword) {
        return Response.builderSuccess(searchService.search(userId, allVisible, keyword));
    }
}
