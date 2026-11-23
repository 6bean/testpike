package com.sixbean.testpike.admin.auth.config;

import com.sixbean.testpike.admin.auth.app.component.JwtTokenService;
import com.sixbean.testpike.admin.auth.app.component.MockUserCatalog;
import com.sixbean.testpike.admin.auth.app.component.PasswordPolicyService;
import com.sixbean.testpike.admin.auth.app.component.SessionTokenService;
import com.sixbean.testpike.admin.auth.infrastructure.remote.client.GatewaySsoClient;
import com.sixbean.testpike.admin.auth.infrastructure.remote.client.MockSsoClient;
import com.sixbean.testpike.admin.auth.infrastructure.remote.client.SsoClient;


import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;


@Configuration
@EnableConfigurationProperties({SsoProperties.class, AuthProperties.class})
public class SsoClientConfig {

    private final SsoProperties props;

    public SsoClientConfig(SsoProperties props) {
        this.props = props;
    }

    @Bean
    public SsoClient ssoClient() {
        if ("gateway".equalsIgnoreCase(props.mode())) {
            return new GatewaySsoClient(props.gateway().baseUrl(), props.gateway(),
                    RestClient.create());
        }
        return new MockSsoClient(props.mock().frontendBase(), new MockUserCatalog());
    }

    @Bean
    public JwtTokenService jwtTokenService() {
        return new JwtTokenService(props.jwt().secret(), props.jwt().ttlSeconds());
    }

 
    @Bean
    public SessionTokenService sessionTokenService(StringRedisTemplate redisTemplate) {
        return new SessionTokenService(redisTemplate);
    }

 
    @Bean
    public PasswordPolicyService passwordPolicyService(
            com.sixbean.testpike.admin.user.infrastructure.remote.client.CoreUserClient coreUserClient,
            StringRedisTemplate redisTemplate) {
        return new PasswordPolicyService(coreUserClient, redisTemplate);
    }
}
