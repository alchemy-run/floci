package io.floci.aws.services.opensearchserverless;

import io.floci.aws.testing.SidecarContainersProfile;

import java.util.HashMap;
import java.util.Map;

/** The sidecar profile, with collections running on OpenSearch. */
public class AossEngineProfile extends SidecarContainersProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("floci.services.opensearch-serverless.engine", "opensearch");
        return overrides;
    }
}
