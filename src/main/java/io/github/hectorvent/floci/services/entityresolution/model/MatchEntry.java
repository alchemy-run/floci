package io.github.hectorvent.floci.services.entityresolution.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A unique-id indexed match produced by GenerateMatchId. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MatchEntry {

    private String uniqueId;
    private String matchId;
    private String matchRule;
    private String inputSourceArn;
    private Map<String, String> attributes;

    public MatchEntry() {
    }

    public String getUniqueId() {
        return uniqueId;
    }

    public void setUniqueId(String uniqueId) {
        this.uniqueId = uniqueId;
    }

    public String getMatchId() {
        return matchId;
    }

    public void setMatchId(String matchId) {
        this.matchId = matchId;
    }

    public String getMatchRule() {
        return matchRule;
    }

    public void setMatchRule(String matchRule) {
        this.matchRule = matchRule;
    }

    public String getInputSourceArn() {
        return inputSourceArn;
    }

    public void setInputSourceArn(String inputSourceArn) {
        this.inputSourceArn = inputSourceArn;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes == null ? null : new LinkedHashMap<>(attributes);
    }
}
