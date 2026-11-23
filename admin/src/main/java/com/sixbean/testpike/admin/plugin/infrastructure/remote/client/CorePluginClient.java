package com.sixbean.testpike.admin.plugin.infrastructure.remote.client;

import com.sixbean.testpike.admin.plugin.api.dto.AppliedPluginView;
import com.sixbean.testpike.admin.plugin.api.dto.AppliedProjectView;
import com.sixbean.testpike.admin.plugin.api.dto.PluginApplyInput;
import com.sixbean.testpike.admin.plugin.api.dto.PluginApplyView;
import com.sixbean.testpike.admin.plugin.api.dto.PluginEnableInput;
import com.sixbean.testpike.admin.plugin.api.dto.PluginUpdateInput;
import com.sixbean.testpike.admin.plugin.api.dto.PluginView;

import java.util.List;


public interface CorePluginClient {

    
    List<PluginView> list(String industry, Boolean enabled);

    
    PluginView register(long operatorId, String content);

    
    PluginView setEnabled(long operatorId, long id, PluginEnableInput input);

    
    PluginApplyView apply(long operatorId, long projectId, long pluginId, PluginApplyInput input);

    
    void uninstall(long operatorId, long projectId, long pluginId);

    
    List<AppliedPluginView> listApplied(long operatorId, long projectId);

    
    PluginView detail(long operatorId, long id);

    
    PluginView update(long operatorId, long id, PluginUpdateInput input);

    
    List<AppliedProjectView> appliedProjects(long operatorId, long id);
}
