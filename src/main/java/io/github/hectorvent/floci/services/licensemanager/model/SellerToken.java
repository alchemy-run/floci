package io.github.hectorvent.floci.services.licensemanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * License Manager refresh token minted against a seller license.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SellerToken {

    private String tokenId;
    private String token;
    private String tokenType;
    private String licenseArn;
    private String expirationTime;
    private String status;
    private List<String> tokenProperties = new ArrayList<>();
    private List<String> roleArns = new ArrayList<>();

    public SellerToken() {
    }

    public String getTokenId() {
        return tokenId;
    }

    public void setTokenId(String tokenId) {
        this.tokenId = tokenId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public String getLicenseArn() {
        return licenseArn;
    }

    public void setLicenseArn(String licenseArn) {
        this.licenseArn = licenseArn;
    }

    public String getExpirationTime() {
        return expirationTime;
    }

    public void setExpirationTime(String expirationTime) {
        this.expirationTime = expirationTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<String> getTokenProperties() {
        return tokenProperties;
    }

    public void setTokenProperties(List<String> tokenProperties) {
        this.tokenProperties = tokenProperties != null ? tokenProperties : new ArrayList<>();
    }

    public List<String> getRoleArns() {
        return roleArns;
    }

    public void setRoleArns(List<String> roleArns) {
        this.roleArns = roleArns != null ? roleArns : new ArrayList<>();
    }
}
