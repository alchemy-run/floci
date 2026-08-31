package io.github.hectorvent.floci.services.ivs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An Amazon IVS playback key pair imported from PEM-encoded ECDSA public key material.
 * Wire names are camelCase.
 */
@RegisterForReflection
public class PlaybackKeyPair {

    private String id;
    private String arn;
    private String name;
    private String fingerprint;
    private String publicKeyMaterial;
    private Map<String, String> tags = new LinkedHashMap<>();

    public PlaybackKeyPair() {
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

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getPublicKeyMaterial() {
        return publicKeyMaterial;
    }

    public void setPublicKeyMaterial(String publicKeyMaterial) {
        this.publicKeyMaterial = publicKeyMaterial;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
