package com.sixbean.testpike.admin.help.infrastructure.remote.client;

import com.sixbean.testpike.admin.help.api.dto.HelpDocView;

import java.util.List;


public interface CoreHelpClient {

    
    List<HelpDocView> list();

    
    List<HelpDocView> detail(String domainCode);
}
