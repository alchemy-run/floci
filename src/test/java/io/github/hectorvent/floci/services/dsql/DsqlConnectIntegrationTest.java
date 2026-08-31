package io.github.hectorvent.floci.services.dsql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dsql.model.Cluster;
import io.github.hectorvent.floci.services.dsql.proxy.DsqlDataPlane;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Connect-facing DSQL control plane: public hostname, PrivateLink service
 * name, and embedded-DNS recognition of {@code *.dsql.{region}.on.aws}.
 * Alchemy's {@code DSQL.Connect} test asserts the same hostname shape and
 * {@code com.amazonaws.*.dsql} VPC endpoint service name.
 */
class DsqlConnectIntegrationTest {

    private static final String REGION = "us-east-1";
    /** Matches Alchemy {@code Connect.test.ts} {@code info.host} assertion. */
    private static final String ENDPOINT = "^[a-z0-9]+\\.dsql\\.[a-z0-9-]+\\.on\\.aws$";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DsqlService service = new DsqlService(
            new InMemoryStorage<>(),
            new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000000"),
            objectMapper);

    @Test
    void createClusterAdvertisesPublicDsqlEndpointAndVpcServiceName() throws Exception {
        Cluster cluster = service.createCluster(REGION, objectMapper.readTree(
                "{\"deletionProtectionEnabled\":false}"));

        assertTrue(cluster.getIdentifier().matches("^[a-z0-9]+$"));
        assertEquals(cluster.getIdentifier() + ".dsql.us-east-1.on.aws", cluster.getEndpoint());
        assertTrue(cluster.getEndpoint().matches(ENDPOINT));
        assertTrue(EmbeddedDnsServer.isDsqlHost(cluster.getEndpoint()));

        Cluster fetched = service.getCluster(REGION, cluster.getIdentifier());
        assertEquals(cluster.getEndpoint(), fetched.getEndpoint());
        assertTrue(EmbeddedDnsServer.isDsqlHost(fetched.getEndpoint()));

        var created = service.toCluster(cluster, false);
        assertEquals(cluster.getEndpoint(), created.get("endpoint").asText());
        assertEquals("ACTIVE", created.get("status").asText());

        var vpc = service.getVpcEndpointServiceName(REGION, cluster.getIdentifier());
        assertEquals("com.amazonaws.us-east-1.dsql", vpc.get("serviceName").asText());
        assertTrue(vpc.get("serviceName").asText().startsWith("com.amazonaws."));
        assertTrue(vpc.get("serviceName").asText().contains("dsql"));
    }

    @Test
    void createClusterWithoutDataPlaneDoesNotThrow() throws Exception {
        Cluster cluster = service.createCluster(REGION, objectMapper.readTree("{}"));
        assertTrue(cluster.getEndpoint().matches(ENDPOINT));
        Cluster deleted = service.deleteCluster(REGION, cluster.getIdentifier());
        assertEquals("DELETING", deleted.getStatus());
    }

    @Test
    void connectAdminUsesStableBackendPasswordMatchingIamProxy() {
        // Alchemy DSQL.Connect({ admin: true }) starts as Postgres user "admin"
        // with an IAM token; the proxy then logs into the shared engine with
        // this password. It must not change across emulator restarts.
        assertEquals("admin", DsqlDataPlane.MASTER_USERNAME);
        assertEquals("postgres", DsqlDataPlane.DATABASE);
        assertEquals("floci-dsql-admin", DsqlDataPlane.MASTER_PASSWORD);
    }
}
