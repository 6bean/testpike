package com.sixbean.testpike.core.plugin.domain.service;

import com.sixbean.testpike.core.plugin.api.dto.AppliedPluginView;
import com.sixbean.testpike.core.plugin.api.dto.AppliedProjectView;
import com.sixbean.testpike.core.plugin.api.dto.PluginApplyView;
import com.sixbean.testpike.core.plugin.api.dto.PluginView;

import java.util.List;


public interface PluginService {

    
    List<PluginView> list(String industry, Boolean enabled);

    /**
 *
 * @throws com.sixbean.testpike.common.exception.ServiceException
 */
    PluginView register(Long operatorId, String yamlContent);

    
    PluginView setEnabled(Long operatorId, long id, boolean enabled);

    
    PluginView detail(long id);

    /**
 *
 * @throws com.sixbean.testpike.common.exception.ServiceException 40001/40301/40401/40901/40904
 */
    PluginView update(Long operatorId, long id, Integer revision, String yamlContent);

    
    List<AppliedProjectView> appliedProjects(long pluginId);

    
    PluginApplyView apply(Long operatorId, long projectId, long pluginId, boolean confirm);

    
    void uninstall(Long operatorId, long projectId, long pluginId);

    
    List<AppliedPluginView> listApplied(long projectId);
}
