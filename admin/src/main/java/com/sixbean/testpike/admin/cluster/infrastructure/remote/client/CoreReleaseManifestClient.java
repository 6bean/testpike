package com.sixbean.testpike.admin.cluster.infrastructure.remote.client;

import com.sixbean.testpike.admin.cluster.api.dto.ExecutorCompatView;
import com.sixbean.testpike.admin.cluster.api.dto.PlatformVersionView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseManifestView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseRegisterRequest;

import java.util.List;


public interface CoreReleaseManifestClient {

    
    ReleaseManifestView register(ReleaseRegisterRequest request);

    
    ReleaseManifestView latest();

    
    List<ReleaseManifestView> listAll();

    
    List<ExecutorCompatView> compatDiff();

    /**
     * Impact pre-check against a hypothetical (not-yet-published) range — feeds the
     * candidate confirm drawer's "N nodes will be marked out-of-range" preview.
     * Verdict stays core-side; the bridge forwards params untouched.
     */
    List<ExecutorCompatView> compatDiffAgainst(String min, String max);

    
    PlatformVersionView platformVersion();
}
