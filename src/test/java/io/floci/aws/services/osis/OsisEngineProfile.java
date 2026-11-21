package io.floci.aws.services.osis;

import io.floci.aws.testing.SidecarContainersProfile;

import java.util.HashMap;
import java.util.Map;

/** The sidecar profile, with pipelines running on Data Prepper. */
public class OsisEngineProfile extends SidecarContainersProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("floci.services.osis.engine", "data-prepper");
        return overrides;
    }
}
