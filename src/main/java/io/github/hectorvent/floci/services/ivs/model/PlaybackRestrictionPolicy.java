package io.github.hectorvent.floci.services.ivs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Amazon IVS playback restriction policy. Wire names are camelCase.
 */
@RegisterForReflection
public class PlaybackRestrictionPolicy {

    private String id;
    private String arn;
    private String name;
    private List<String> allowedCountries = new ArrayList<>();
    private List<String> allowedOrigins = new ArrayList<>();
    private boolean enableStrictOriginEnforcement;
    private Map<String, String> tags = new LinkedHashMap<>();

    public PlaybackRestrictionPolicy() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getAllowedCountries() {
        return allowedCountries;
    }

    public void setAllowedCountries(List<String> allowedCountries) {
        this.allowedCountries = allowedCountries == null ? new ArrayList<>() : new ArrayList<>(allowedCountries);
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }

    public boolean isEnableStrictOriginEnforcement() {
        return enableStrictOriginEnforcement;
    }

    public void setEnableStrictOriginEnforcement(boolean enableStrictOriginEnforcement) {
        this.enableStrictOriginEnforcement = enableStrictOriginEnforcement;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
