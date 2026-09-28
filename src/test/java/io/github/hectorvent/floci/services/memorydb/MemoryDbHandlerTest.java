package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.memorydb.model.Acl;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.ClusterStatus;
import io.github.hectorvent.floci.services.memorydb.model.Endpoint;
import io.github.hectorvent.floci.services.memorydb.model.User;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryDbHandlerTest {

    private MemoryDbService service;
    private MemoryDbHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = mock(MemoryDbService.class);
        handler = new MemoryDbHandler(service, objectMapper);
        when(service.createCluster(any(), any())).thenAnswer(inv -> {
            Cluster spec = inv.getArgument(0);
            spec.setStatus(ClusterStatus.AVAILABLE);
            spec.setClusterEndpoint(new Endpoint("localhost", 6400));
            spec.setCreatedAt(Instant.now());
            return spec;
        });
        when(service.clustersUsingAcl(any())).thenReturn(List.of());
        when(service.aclNamesForUser(any())).thenReturn(List.of());
    }

    @Test
    void createClusterPropagatesAclNameToSpec() throws Exception {
        JsonNode request = objectMapper.readTree(
                "{\"ClusterName\":\"secure\",\"ACLName\":\"app-acl\"}");

        handler.handle("CreateCluster", request, "us-east-1");

        ArgumentCaptor<Cluster> captor = ArgumentCaptor.forClass(Cluster.class);
        verify(service).createCluster(captor.capture(), eq("us-east-1"));
        assertEquals("app-acl", captor.getValue().getAclName());
    }

    @Test
    void createUserParsesAuthenticationMode() throws Exception {
        when(service.createUser(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        JsonNode request = objectMapper.readTree(
                "{\"UserName\":\"app-user\","
                        + "\"AccessString\":\"on ~* +@all\","
                        + "\"AuthenticationMode\":{\"Type\":\"password\",\"Passwords\":[\"s3cret\"]}}");

        Response response = handler.handle("CreateUser", request, "us-east-1");
        assertEquals(200, response.getStatus());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(service).createUser(captor.capture(), eq("us-east-1"));
        User spec = captor.getValue();
        assertEquals("app-user", spec.getName());
        assertEquals(AuthMode.PASSWORD, spec.getAuthMode());
        assertEquals(List.of("s3cret"), spec.getPasswords());
    }

    @Test
    void createAclParsesUserNames() throws Exception {
        when(service.createAcl(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        JsonNode request = objectMapper.readTree(
                "{\"ACLName\":\"app-acl\",\"UserNames\":[\"app-user\"]}");

        Response response = handler.handle("CreateACL", request, "us-east-1");
        assertEquals(200, response.getStatus());

        ArgumentCaptor<Acl> captor = ArgumentCaptor.forClass(Acl.class);
        verify(service).createAcl(captor.capture(), eq("us-east-1"));
        assertEquals(List.of("app-user"), captor.getValue().getUserNames());
    }

    @Test
    void createClusterEnablesTlsUnlessTheRequestDisablesIt() throws Exception {
        handler.handle("CreateCluster", objectMapper.readTree(
                "{\"ClusterName\":\"tls-default\",\"ACLName\":\"app-acl\"}"), "us-east-1");
        handler.handle("CreateCluster", objectMapper.readTree(
                "{\"ClusterName\":\"tls-off\",\"ACLName\":\"open-access\",\"TLSEnabled\":false}"), "us-east-1");

        ArgumentCaptor<Cluster> captor = ArgumentCaptor.forClass(Cluster.class);
        verify(service, org.mockito.Mockito.times(2)).createCluster(captor.capture(), eq("us-east-1"));
        assertEquals(true, captor.getAllValues().get(0).isTlsEnabled());
        assertEquals(false, captor.getAllValues().get(1).isTlsEnabled());
    }

    @Test
    void createClusterRecordsAndReportsSecurityGroups() throws Exception {
        Response response = handler.handle("CreateCluster", objectMapper.readTree(
                "{\"ClusterName\":\"sg\",\"ACLName\":\"app-acl\",\"SecurityGroupIds\":[\"sg-1\"]}"), "us-east-1");

        JsonNode cluster = objectMapper.valueToTree(response.getEntity()).path("Cluster");
        assertEquals("sg-1", cluster.path("SecurityGroups").get(0).path("SecurityGroupId").asText());
        assertEquals("active", cluster.path("SecurityGroups").get(0).path("Status").asText());
    }

    @Test
    void updateAclPassesTheMembershipDelta() throws Exception {
        Acl updated = new Acl();
        updated.setName("app-acl");
        updated.setStatus("active");
        updated.setUserNames(List.of("new-user"));
        when(service.updateAcl(any(), any(), any(), any())).thenReturn(updated);

        Response response = handler.handle("UpdateACL", objectMapper.readTree(
                "{\"ACLName\":\"app-acl\",\"UserNamesToAdd\":[\"new-user\"],\"UserNamesToRemove\":[\"old-user\"]}"),
                "us-east-1");

        assertEquals(200, response.getStatus());
        verify(service).updateAcl("app-acl", List.of("new-user"), List.of("old-user"), "us-east-1");
        JsonNode body = objectMapper.valueToTree(response.getEntity());
        assertEquals("new-user", body.path("ACL").path("UserNames").get(0).asText());
    }

    @Test
    void unknownOperationReturns400() throws Exception {
        JsonNode request = objectMapper.readTree("{}");
        Response response = handler.handle("Bogus", request, "us-east-1");
        assertEquals(400, response.getStatus());
    }
}
