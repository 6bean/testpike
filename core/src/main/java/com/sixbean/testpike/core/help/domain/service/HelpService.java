package com.sixbean.testpike.core.help.domain.service;

import com.sixbean.testpike.core.help.api.dto.HelpDocInfo;

import java.util.List;


public interface HelpService {

    
    List<HelpDocInfo> list();

    
    List<HelpDocInfo> detail(String domainCode);

    
    String refreshVersion(String version);
}
