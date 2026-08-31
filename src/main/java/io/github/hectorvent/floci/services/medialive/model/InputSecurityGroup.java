package io.github.hectorvent.floci.services.medialive.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An AWS Elemental MediaLive input security group. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InputSecurityGroup {

    private String id;
    private String arn;
    private String state;
    private String region;
    private List<String> whitelistRules = new ArrayList<>();
    private List<String> inputs = new ArrayList<>();
    private List<String> channels = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public InputSecurityGroup() {
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public List<String> getWhitelistRules() {
        return whitelistRules;
    }

    public void setWhitelistRules(List<String> whitelistRules) {
        this.whitelistRules = whitelistRules == null ? new ArrayList<>() : new ArrayList<>(whitelistRules);
    }

    public List<String> getInputs() {
        return inputs;
    }

    public void setInputs(List<String> inputs) {
        this.inputs = inputs == null ? new ArrayList<>() : new ArrayList<>(inputs);
    }

    public List<String> getChannels() {
        return channels;
    }

    public void setChannels(List<String> channels) {
        this.channels = channels == null ? new ArrayList<>() : new ArrayList<>(channels);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
