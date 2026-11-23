package com.sixbean.testpike.core.script.domain.component;

import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class ScriptSharedComponentsConfig {

    @Bean
    public ActionJsonValidator actionJsonValidator() {
        return new ActionJsonValidator();
    }
}
