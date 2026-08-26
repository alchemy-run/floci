package io.github.hectorvent.floci.services.docdbelastic;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.docdbelastic.model.Cluster;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocDbElasticServiceTest {

    private static final String REGION = "us-east-1";
    private static final String MISSING_ARN =
            "arn:aws:docdb-elastic:us-east-1:000000000000:cluster/00000000-0000-0000-0000-000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DocDbElasticService service = new DocDbElasticService(
            new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000000"),
            objectMapper);

    @Test
    void getMissingClusterThrowsResourceNotFound() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.getCluster(REGION, MISSING_ARN));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
        assertEquals("cluster", error.getExtendedData().get("resourceType"));
    }

    @Test
    void createThenGetRoundTripsNameArnAndTags() throws Exception {
        Cluster created = service.createCluster(REGION, objectMapper.readTree("""
                {
                  "clusterName": "docs",
                  "authType": "PLAIN_TEXT",
                  "adminUserName": "alchemyadmin",
                  "adminUserPassword": "AlchemyTestPassw0rd",
                  "shardCapacity": 2,
                  "shardCount": 1,
                  "tags": {"fixture": "docdb-elastic-cluster"}
                }
                """));

        assertEquals("docs", created.getClusterName());
        assertTrue(created.getClusterArn().contains(":cluster/"));
        assertEquals("ACTIVE", created.getStatus());
        assertTrue(created.getClusterEndpoint().contains("docdb-elastic"));
        assertEquals("alchemyadmin", created.getAdminUserName());
        assertEquals("PLAIN_TEXT", created.getAuthType());
        assertEquals(2, created.getShardCapacity());
        assertEquals(1, created.getShardCount());
        assertEquals("docdb-elastic-cluster", created.getTags().get("fixture"));

        Cluster fetched = service.getCluster(REGION, created.getClusterArn());
        assertEquals(created.getClusterArn(), fetched.getClusterArn());
        assertEquals("ACTIVE", fetched.getStatus());
    }

    @Test
    void duplicateNameConflictsAndDeleteRemovesCluster() throws Exception {
        service.createCluster(REGION, objectMapper.readTree("""
                {
                  "clusterName": "docs-dup",
                  "authType": "PLAIN_TEXT",
                  "adminUserName": "alchemyadmin",
                  "adminUserPassword": "AlchemyTestPassw0rd",
                  "shardCapacity": 2,
                  "shardCount": 1
                }
                """));

        AwsException conflict = assertThrows(
                AwsException.class,
                () -> service.createCluster(REGION, objectMapper.readTree("""
                        {
                          "clusterName": "docs-dup",
                          "authType": "PLAIN_TEXT",
                          "adminUserName": "alchemyadmin",
                          "adminUserPassword": "AlchemyTestPassw0rd",
                          "shardCapacity": 2,
                          "shardCount": 1
                        }
                        """)));
        assertEquals("ConflictException", conflict.getErrorCode());
        assertEquals(409, conflict.getHttpStatus());

        Cluster created = service.createCluster(REGION, objectMapper.readTree("""
                {
                  "clusterName": "docs-del",
                  "authType": "PLAIN_TEXT",
                  "adminUserName": "alchemyadmin",
                  "adminUserPassword": "AlchemyTestPassw0rd",
                  "shardCapacity": 2,
                  "shardCount": 1
                }
                """));
        service.deleteCluster(REGION, created.getClusterArn());
        AwsException gone = assertThrows(
                AwsException.class,
                () -> service.getCluster(REGION, created.getClusterArn()));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }
}
