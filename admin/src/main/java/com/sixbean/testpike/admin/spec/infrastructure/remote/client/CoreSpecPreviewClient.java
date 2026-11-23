package com.sixbean.testpike.admin.spec.infrastructure.remote.client;

import com.sixbean.testpike.admin.spec.api.dto.ApiSpecPreviewView;
import com.sixbean.testpike.admin.spec.api.dto.SpecExtractDispatchRequest;
import com.sixbean.testpike.admin.spec.api.dto.SpecExtractEstimateView;
import com.sixbean.testpike.admin.spec.api.dto.SpecPreviewApplyView;

import java.util.List;


public interface CoreSpecPreviewClient {

    
    ApiSpecPreviewView generate(long specId);

    
    ApiSpecPreviewView get(long previewId);

    
    ApiSpecPreviewView latestByDocument(long documentId);

    
    SpecPreviewApplyView apply(long previewId, List<String> selectedKeys);

 
    SpecExtractEstimateView dispatchExtract(SpecExtractDispatchRequest request);
}
