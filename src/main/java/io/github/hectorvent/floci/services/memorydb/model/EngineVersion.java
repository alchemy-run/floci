package io.github.hectorvent.floci.services.memorydb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EngineVersion {

    private final String engine;
    private final String engineVersion;
    private final String enginePatchVersion;
    private final String parameterGroupFamily;
    private final boolean defaultVersion;

    public EngineVersion(String engine, String engineVersion, String enginePatchVersion,
                         String parameterGroupFamily, boolean defaultVersion) {
        this.engine = engine;
        this.engineVersion = engineVersion;
        this.enginePatchVersion = enginePatchVersion;
        this.parameterGroupFamily = parameterGroupFamily;
        this.defaultVersion = defaultVersion;
    }

    public String getEngine() { return engine; }
    public String getEngineVersion() { return engineVersion; }
    public String getEnginePatchVersion() { return enginePatchVersion; }
    public String getParameterGroupFamily() { return parameterGroupFamily; }
    public boolean isDefaultVersion() { return defaultVersion; }
}
