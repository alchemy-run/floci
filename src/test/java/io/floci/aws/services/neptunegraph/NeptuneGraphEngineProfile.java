package io.floci.aws.services.neptunegraph;

import io.floci.aws.testing.SidecarContainersProfile;

import java.util.HashMap;
import java.util.Map;

/** The sidecar profile, with graphs running their Neo4j query engine. */
public class NeptuneGraphEngineProfile extends SidecarContainersProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("floci.services.neptune-graph.query-engine", "neo4j");
        return overrides;
    }
}
