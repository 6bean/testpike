package com.sixbean.testpike.aiservice.popuplearn.domain.service;

import com.sixbean.testpike.aiservice.popuplearn.api.dto.PopupLearnRequest;

import java.util.Map;


public interface PopupLearnService {

    
    Map<String, Object> learn(PopupLearnRequest request);
}
