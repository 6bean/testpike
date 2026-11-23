package com.sixbean.testpike.admin.search.infrastructure.remote.client;

import com.sixbean.testpike.admin.search.api.dto.SearchResultsView;


public interface CoreSearchClient {

    
    SearchResultsView search(boolean allVisible, String keyword);
}
