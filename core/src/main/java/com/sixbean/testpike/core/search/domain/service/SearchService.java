package com.sixbean.testpike.core.search.domain.service;

import com.sixbean.testpike.core.search.api.dto.SearchResults;


public interface SearchService {

    
    SearchResults search(long userId, boolean allVisible, String keyword);
}
