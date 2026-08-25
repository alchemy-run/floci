package io.github.hectorvent.floci.services.appintegrations.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An association between a DataIntegration and an approved client. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class DataIntegrationAssociation {

    private String dataIntegrationAssociationArn;
    private String dataIntegrationAssociationId;
    private String dataIntegrationArn;
    private String clientId;
    private String destinationURI;
    private JsonNode executionConfiguration;
    private JsonNode lastExecutionStatus;
    private Map<String, String> clientAssociationMetadata;

    public DataIntegrationAssociation() {
    }

    public String getDataIntegrationAssociationArn() {
        return dataIntegrationAssociationArn;
    }

    public void setDataIntegrationAssociationArn(String dataIntegrationAssociationArn) {
        this.dataIntegrationAssociationArn = dataIntegrationAssociationArn;
    }

    public String getDataIntegrationAssociationId() {
        return dataIntegrationAssociationId;
    }

    public void setDataIntegrationAssociationId(String dataIntegrationAssociationId) {
        this.dataIntegrationAssociationId = dataIntegrationAssociationId;
    }

    public String getDataIntegrationArn() {
        return dataIntegrationArn;
    }

    public void setDataIntegrationArn(String dataIntegrationArn) {
        this.dataIntegrationArn = dataIntegrationArn;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getDestinationURI() {
        return destinationURI;
    }

    public void setDestinationURI(String destinationURI) {
        this.destinationURI = destinationURI;
    }

    public JsonNode getExecutionConfiguration() {
        return executionConfiguration == null ? null : executionConfiguration.deepCopy();
    }

    public void setExecutionConfiguration(JsonNode executionConfiguration) {
        this.executionConfiguration = executionConfiguration == null ? null : executionConfiguration.deepCopy();
    }

    public JsonNode getLastExecutionStatus() {
        return lastExecutionStatus == null ? null : lastExecutionStatus.deepCopy();
    }

    public void setLastExecutionStatus(JsonNode lastExecutionStatus) {
        this.lastExecutionStatus = lastExecutionStatus == null ? null : lastExecutionStatus.deepCopy();
    }

    public Map<String, String> getClientAssociationMetadata() {
        return clientAssociationMetadata;
    }

    public void setClientAssociationMetadata(Map<String, String> clientAssociationMetadata) {
        this.clientAssociationMetadata = clientAssociationMetadata == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(clientAssociationMetadata);
    }
}
