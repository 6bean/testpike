package com.sixbean.testpike.aiservice.matricesample.domain.service;

import com.sixbean.testpike.aiservice.matricesample.api.dto.MatrixSampleRequest;

import java.util.Map;


public interface MatrixSampleService {

    
    Map<String, Object> sample(MatrixSampleRequest request);
}
