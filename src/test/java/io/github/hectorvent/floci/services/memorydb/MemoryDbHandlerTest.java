package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.memorydb.model.Acl;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.ClusterStatus;
import io.github.hectorvent.floci.services.memorydb.model.Endpoint;
import io.github.hectorvent.floci.services.memorydb.model.EngineVersion;
import io.github.hectorvent.floci.services.memorydb.model.SubnetGroup;
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
    void updateAclForwardsAddAndRemoveLists() throws Exception {
        when(service.updateAcl(any(), any(), any())).thenAnswer(inv -> {
            Acl acl = new Acl();
            acl.setName(inv.getArgument(0));
            acl.setUserNames(inv.getArgument(1));
            return acl;
        });
        JsonNode request = objectMapper.readTree(
                "{\"ACLName\":\"app-acl\",\"UserNamesToAdd\":[\"new-user\"],\"UserNamesToRemove\":[\"old-user\"]}");

        Response response = handler.handle("UpdateACL", request, "us-east-1");
        assertEquals(200, response.getStatus());
        verify(service).updateAcl(eq("app-acl"), eq(List.of("new-user")), eq(List.of("old-user")));
    }

    @Test
    void describeEngineVersionsReturnsCatalog() throws Exception {
        when(service.describeEngineVersions(eq("valkey"), eq(null), eq(null), eq(false)))
                .thenReturn(List.of(new EngineVersion(
                        "valkey", "8.1", "8.1.1", "memorydb_valkey8", true)));

        Response response = handler.handle("DescribeEngineVersions",
                objectMapper.readTree("{\"Engine\":\"valkey\"}"), "us-east-1");
        assertEquals(200, response.getStatus());
    }

    @Test
    void unknownOperationReturns400() throws Exception {
        JsonNode request = objectMapper.readTree("{}");
        Response response = handler.handle("Bogus", request, "us-east-1");
        assertEquals(400, response.getStatus());
    }

    @Test
    void createClusterDefaultsTlsEnabledAndParsesNetwork() throws Exception {
        JsonNode request = objectMapper.readTree(
                "{\"ClusterName\":\"cache\",\"ACLName\":\"app-acl\","
                        + "\"SubnetGroupName\":\"cluster-subnets\","
                        + "\"SecurityGroupIds\":[\"sg-123\"],"
                        + "\"NumShards\":1,\"NumReplicasPerShard\":0}");

        handler.handle("CreateCluster", request, "us-east-1");

        ArgumentCaptor<Cluster> captor = ArgumentCaptor.forClass(Cluster.class);
        verify(service).createCluster(captor.capture(), eq("us-east-1"));
        Cluster spec = captor.getValue();
        assertEquals(true, spec.isTlsEnabled());
        assertEquals("cluster-subnets", spec.getSubnetGroupName());
        assertEquals(List.of("sg-123"), spec.getSecurityGroupIds());
        assertEquals(1, spec.getNumberOfShards());
        assertEquals(0, spec.getNumReplicasPerShard());
    }

    @Test
    void describeSubnetGroupsMissingReturnsNotFound() throws Exception {
        when(service.describeSubnetGroups("missing")).thenThrow(
                new AwsException("SubnetGroupNotFoundFault", "Subnet group missing not found.", 404));
        Response response = handler.handle("DescribeSubnetGroups",
                objectMapper.readTree("{\"SubnetGroupName\":\"missing\"}"), "us-east-1");
        assertEquals(404, response.getStatus());
    }

    @Test
    void createSubnetGroupParsesSubnetIds() throws Exception {
        when(service.createSubnetGroup(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        JsonNode request = objectMapper.readTree(
                "{\"SubnetGroupName\":\"cluster-subnets\","
                        + "\"Description\":\"alchemy memorydb cluster subnets\","
                        + "\"SubnetIds\":[\"subnet-aaa\",\"subnet-bbb\"]}");

        Response response = handler.handle("CreateSubnetGroup", request, "us-east-1");
        assertEquals(200, response.getStatus());

        ArgumentCaptor<SubnetGroup> captor = ArgumentCaptor.forClass(SubnetGroup.class);
        verify(service).createSubnetGroup(captor.capture(), eq("us-east-1"));
        assertEquals("cluster-subnets", captor.getValue().getName());
        assertEquals(List.of("subnet-aaa", "subnet-bbb"),
                captor.getValue().getSubnets().stream().map(SubnetGroup.SubnetRef::getIdentifier).toList());
    }
}
