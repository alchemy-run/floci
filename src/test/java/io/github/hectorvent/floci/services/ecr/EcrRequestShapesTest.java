package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The registry setting actions checked against the vendored ECR model constraints. */
class EcrRequestShapesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DESTINATION = "{\"region\":\"us-west-2\",\"registryId\":\"123456789012\"}";

    private static JsonNode json(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException(body, e);
        }
    }

    private static String refusal(String operation, String body) {
        AwsException e = assertThrows(AwsException.class, () -> EcrRequestShapes.check(operation, json(body)), body);
        assertEquals("InvalidParameterException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        return e.getMessage();
    }

    private static String replication(String rules) {
        return "{\"replicationConfiguration\":{\"rules\":[" + rules + "]}}";
    }

    private static String rule(String destinations, String filters) {
        return "{\"destinations\":[" + destinations + "]" + (filters == null ? "" : ",\"repositoryFilters\":[" + filters + "]")
                + "}";
    }

    @Test
    void registryPolicyLengthAndPresence() {
        assertEquals("Invalid parameter at 'policyText' failed to satisfy constraint: "
                        + "'Member must have length less than or equal to 10240'",
                refusal("PutRegistryPolicy", "{\"policyText\":\"" + "x".repeat(10241) + "\"}"));
        assertEquals("Invalid parameter at 'policyText' failed to satisfy constraint: 'Member must not be null'",
                refusal("PutRegistryPolicy", "{}"));
        assertDoesNotThrow(() -> EcrRequestShapes.check("PutRegistryPolicy",
                json("{\"policyText\":\"" + "x".repeat(10240) + "\"}")));
    }

    @Test
    void replicationRulesDestinationsAndFilters() {
        assertEquals("Invalid parameter at 'replicationConfiguration.rules.destinations.region' failed to satisfy "
                        + "constraint: 'must satisfy regular expression '[0-9a-z-]{2,25}''",
                refusal("PutReplicationConfiguration", replication(rule(
                        "{\"region\":\"US_WEST\",\"registryId\":\"123456789012\"}", null))));
        assertEquals("Invalid parameter at 'replicationConfiguration.rules.destinations.registryId' failed to satisfy "
                        + "constraint: 'must satisfy regular expression '[0-9]{12}''",
                refusal("PutReplicationConfiguration", replication(rule(
                        "{\"region\":\"us-west-2\",\"registryId\":\"12345\"}", null))));
        assertEquals("Invalid parameter at 'replicationConfiguration.rules.repositoryFilters.filterType' failed to "
                        + "satisfy constraint: 'Member must satisfy enum value set: [PREFIX_MATCH]'",
                refusal("PutReplicationConfiguration", replication(rule(DESTINATION,
                        "{\"filter\":\"prod\",\"filterType\":\"WILDCARD\"}"))));
        assertEquals("Invalid parameter at 'replicationConfiguration.rules.repositoryFilters' failed to satisfy "
                        + "constraint: 'Member must have length greater than or equal to 1'",
                refusal("PutReplicationConfiguration", replication(rule(DESTINATION, ""))));
        assertEquals("Invalid parameter at 'replicationConfiguration.rules' failed to satisfy constraint: "
                        + "'Member must have length less than or equal to 25'",
                refusal("PutReplicationConfiguration",
                        replication(String.join(",", Collections.nCopies(26, rule(DESTINATION, null))))));
        assertEquals("Invalid parameter at 'replicationConfiguration.rules.destinations' failed to satisfy "
                        + "constraint: 'Member must not be null'",
                refusal("PutReplicationConfiguration", replication("{}")));
        assertEquals("Invalid parameter at 'replicationConfiguration' failed to satisfy constraint: "
                        + "'Member must not be null'",
                refusal("PutReplicationConfiguration", "{}"));
        refusal("PutReplicationConfiguration", replication(rule(DESTINATION,
                "{\"filter\":\"Prod\",\"filterType\":\"PREFIX_MATCH\"}")));
        refusal("PutReplicationConfiguration", replication(rule(DESTINATION,
                "{\"filter\":\"p\",\"filterType\":\"PREFIX_MATCH\"}")));
        refusal("PutReplicationConfiguration", replication(rule(
                String.join(",", Collections.nCopies(101, DESTINATION)), null)));

        assertDoesNotThrow(() -> EcrRequestShapes.check("PutReplicationConfiguration",
                json(replication(String.join(",", Collections.nCopies(25, rule(DESTINATION,
                        "{\"filter\":\"prod/api\",\"filterType\":\"PREFIX_MATCH\"}")))))));
        // An empty list is how a configuration is cleared.
        assertDoesNotThrow(() -> EcrRequestShapes.check("PutReplicationConfiguration", json(replication(""))));
    }

    @Test
    void scanningTypeFrequencyAndFilters() {
        assertEquals("Invalid parameter at 'scanType' failed to satisfy constraint: "
                        + "'Member must satisfy enum value set: [BASIC, ENHANCED]'",
                refusal("PutRegistryScanningConfiguration", "{\"scanType\":\"FULL\"}"));
        assertEquals("Invalid parameter at 'rules.scanFrequency' failed to satisfy constraint: "
                        + "'Member must satisfy enum value set: [SCAN_ON_PUSH, CONTINUOUS_SCAN, MANUAL]'",
                refusal("PutRegistryScanningConfiguration", "{\"scanType\":\"ENHANCED\",\"rules\":[{\"scanFrequency\":"
                        + "\"HOURLY\",\"repositoryFilters\":[{\"filter\":\"*\",\"filterType\":\"WILDCARD\"}]}]}"));
        assertEquals("Invalid parameter at 'rules.repositoryFilters.filterType' failed to satisfy constraint: "
                        + "'Member must satisfy enum value set: [WILDCARD]'",
                refusal("PutRegistryScanningConfiguration", "{\"rules\":[{\"scanFrequency\":\"SCAN_ON_PUSH\","
                        + "\"repositoryFilters\":[{\"filter\":\"*\",\"filterType\":\"PREFIX_MATCH\"}]}]}"));
        refusal("PutRegistryScanningConfiguration", "{\"rules\":[{\"scanFrequency\":\"SCAN_ON_PUSH\","
                + "\"repositoryFilters\":[{\"filter\":\"Bad Filter\",\"filterType\":\"WILDCARD\"}]}]}");
        refusal("PutRegistryScanningConfiguration", "{\"rules\":[{\"scanFrequency\":\"SCAN_ON_PUSH\"}]}");
        String scanRule = "{\"scanFrequency\":\"SCAN_ON_PUSH\",\"repositoryFilters\":[{\"filter\":\"*\","
                + "\"filterType\":\"WILDCARD\"}]}";
        assertEquals("Invalid parameter at 'rules' failed to satisfy constraint: "
                        + "'Member must have length less than or equal to 2'",
                refusal("PutRegistryScanningConfiguration",
                        "{\"rules\":[" + String.join(",", Collections.nCopies(3, scanRule)) + "]}"));
        assertEquals("Invalid parameter at 'scanType' failed to satisfy constraint: 'Member must be a string'",
                refusal("PutRegistryScanningConfiguration", "{\"scanType\":1}"));

        assertDoesNotThrow(() -> EcrRequestShapes.check("PutRegistryScanningConfiguration",
                json("{\"scanType\":\"ENHANCED\",\"rules\":[" + scanRule + "," + scanRule + "]}")));
    }

    @Test
    void readsTakeAnEmptyBody() {
        for (String operation : new String[] {"GetRegistryPolicy", "DeleteRegistryPolicy", "DescribeRegistry",
                "GetRegistryScanningConfiguration"}) {
            assertDoesNotThrow(() -> EcrRequestShapes.check(operation, json("{}")), operation);
        }
    }
}
