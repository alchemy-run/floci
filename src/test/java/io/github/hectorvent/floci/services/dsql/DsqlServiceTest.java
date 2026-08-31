package io.github.hectorvent.floci.services.dsql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dsql.model.Cluster;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DsqlServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DsqlService service = new DsqlService(
            new InMemoryStorage<>(),
            new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000401"),
            objectMapper);

    @Test
    void getMissingClusterThrowsResourceNotFound() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.getCluster(REGION, "nonexistentcluster000000"));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void createThenGetRoundTripsIdentifierArnAndTags() throws Exception {
        Cluster created = service.createCluster(REGION, objectMapper.readTree("""
                {
                  "deletionProtectionEnabled": false,
                  "tags": {"app": "alchemy-test"}
                }
                """));

        assertEquals(26, created.getIdentifier().length());
        assertTrue(created.getArn().contains(":cluster/" + created.getIdentifier()));
        assertEquals("ACTIVE", created.getStatus());
        assertFalse(created.isDeletionProtectionEnabled());
        assertTrue(created.getEndpoint().contains(created.getIdentifier()));
        assertEquals("alchemy-test", created.getTags().get("app"));

        Cluster fetched = service.getCluster(REGION, created.getIdentifier());
        assertEquals(created.getArn(), fetched.getArn());
        assertEquals("alchemy-test", fetched.getTags().get("app"));

        var vpc = service.getVpcEndpointServiceName(REGION, created.getIdentifier());
        assertEquals("com.amazonaws.us-east-1.dsql", vpc.get("serviceName").asText());
    }

    @Test
    void updateEnablesDeletionProtectionAndDeleteRequiresItOff() throws Exception {
        Cluster created = service.createCluster(REGION, objectMapper.readTree("{}"));
        Cluster updated = service.updateCluster(REGION, created.getIdentifier(), objectMapper.readTree("""
                {"deletionProtectionEnabled": true}
                """));
        assertTrue(updated.isDeletionProtectionEnabled());

        AwsException protectedDelete = assertThrows(
                AwsException.class,
                () -> service.deleteCluster(REGION, created.getIdentifier()));
        assertEquals("ValidationException", protectedDelete.getErrorCode());
        assertEquals("deletionProtectionEnabled", protectedDelete.getExtendedData().get("reason"));

        service.updateCluster(REGION, created.getIdentifier(), objectMapper.readTree("""
                {"deletionProtectionEnabled": false}
                """));
        service.deleteCluster(REGION, created.getIdentifier());

        AwsException gone = assertThrows(
                AwsException.class,
                () -> service.getCluster(REGION, created.getIdentifier()));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    @Test
    void putGetAndUpdateClusterPolicyBumpsVersion() throws Exception {
        Cluster created = service.createCluster(REGION, objectMapper.readTree("{}"));
        AwsException missing = assertThrows(
                AwsException.class,
                () -> service.getClusterPolicy(REGION, created.getIdentifier()));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());

        Cluster attached = service.putClusterPolicy(REGION, created.getIdentifier(), objectMapper.readTree("""
                {"policy":"{\\"Version\\":\\"2012-10-17\\",\\"Statement\\":[]}"}
                """));
        assertEquals("1", attached.getPolicyVersion());
        assertTrue(attached.getPolicy().contains("2012-10-17"));

        Cluster updated = service.putClusterPolicy(REGION, created.getIdentifier(), objectMapper.readTree("""
                {
                  "policy": "{\\"Version\\":\\"2012-10-17\\",\\"Statement\\":[{\\"Sid\\":\\"DenyNonVpcConnect\\"}]}",
                  "expectedPolicyVersion": "1"
                }
                """));
        assertEquals("2", updated.getPolicyVersion());
        assertTrue(updated.getPolicy().contains("DenyNonVpcConnect"));
        assertNotEquals("1", updated.getPolicyVersion());
    }
}
