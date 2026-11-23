package com.sixbean.testpike.core.cluster.domain.component;

import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Download-host whitelist reader (K8 key {@code testpike.cluster.artifact-allowed-hosts},
 * CSV; the installkit mirror host is merged at read time — mirror re-routing hot-updates
 * without a restart, same posture as the upstream probe service, no secondary cache).
 * <p>Single source consumed by BOTH the register gate (ReleaseManifestServiceImpl) and
 * the pre-dispatch reachability prober — a second whitelist reader copy is banned (red line 6).
 */
@Component
public class ArtifactHostPolicy {

    /** CSV whitelist key (seed: github.com,objects.githubusercontent.com). */
    public static final String ALLOWED_HOSTS_KEY = "testpike.cluster.artifact-allowed-hosts";
    /** Mirror base-url key whose host is merged into the whitelist on every read. */
    public static final String MIRROR_KEY = "installkit.mirror-base-url";

    private final ConfigCacheHolder configCacheHolder;

    public ArtifactHostPolicy(ConfigCacheHolder configCacheHolder) {
        this.configCacheHolder = configCacheHolder;
    }

    /** Current effective whitelist (config CSV + mirror host), lower-cased bare hosts. */
    public Set<String> allowedHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        String csv = configCacheHolder.get(ALLOWED_HOSTS_KEY);
        if (csv != null) {
            for (String part : csv.split(",")) {
                String host = ReleaseArtifactRules.hostOf(part.trim());
                if (host != null) {
                    hosts.add(host);
                }
            }
        }
        String mirror = configCacheHolder.get(MIRROR_KEY);
        if (mirror != null && !mirror.isBlank()) {
            String host = ReleaseArtifactRules.hostOf(mirror);
            if (host != null) {
                hosts.add(host);
            }
        }
        return hosts;
    }
}
