package io.github.hectorvent.floci.services.servicecatalog.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Portfolio {

    private String id;
    private String arn;
    private String displayName;
    private String description;
    private String providerName;
    private long createdTime;
    private String region;
    private String idempotencyToken;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<String> productIds = new ArrayList<>();
    private List<PrincipalAssociation> principals = new ArrayList<>();

    public Portfolio() {
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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getIdempotencyToken() {
        return idempotencyToken;
    }

    public void setIdempotencyToken(String idempotencyToken) {
        this.idempotencyToken = idempotencyToken;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public List<String> getProductIds() {
        return productIds;
    }

    public void setProductIds(List<String> productIds) {
        this.productIds = productIds != null ? productIds : new ArrayList<>();
    }

    public List<PrincipalAssociation> getPrincipals() {
        return principals;
    }

    public void setPrincipals(List<PrincipalAssociation> principals) {
        this.principals = principals != null ? principals : new ArrayList<>();
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrincipalAssociation {
        private String principalArn;
        private String principalType;

        public PrincipalAssociation() {
        }

        public PrincipalAssociation(String principalArn, String principalType) {
            this.principalArn = principalArn;
            this.principalType = principalType;
        }

        public String getPrincipalArn() {
            return principalArn;
        }

        public void setPrincipalArn(String principalArn) {
            this.principalArn = principalArn;
        }

        public String getPrincipalType() {
            return principalType;
        }

        public void setPrincipalType(String principalType) {
            this.principalType = principalType;
        }
    }
}
