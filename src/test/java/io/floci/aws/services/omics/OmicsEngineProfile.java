package io.floci.aws.services.omics;

import io.floci.aws.testing.SidecarContainersProfile;

import java.util.HashMap;
import java.util.Map;

/** The sidecar profile, with runs executing on their languages' engines. */
public class OmicsEngineProfile extends SidecarContainersProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("floci.services.omics.engine", "docker");
        return overrides;
    }
}
