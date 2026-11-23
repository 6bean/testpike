package com.sixbean.testpike.core.retention.domain.service;

import com.sixbean.testpike.core.retention.api.dto.RetentionItem;

import java.util.List;


public interface RetentionService {

    
    List<RetentionItem> preview();

    
    List<RetentionItem> run();
}
