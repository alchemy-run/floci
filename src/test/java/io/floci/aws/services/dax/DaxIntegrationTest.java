package io.floci.aws.services.dax;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAX over JSON 1.1: subnet groups over EC2 subnets, parameter groups and their reboot-applied values, the
 * cluster lifecycle with node placement, resizing, reboots, tags and events. The test profile settles
 * transitions immediately, so a cluster reports {@code creating} from CreateCluster and {@code available} on the
 * next read.
 */
@QuarkusTest
class DaxIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/dax/aws4_request";
    private static final String EC2_AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ec2/aws4_request";
    private static final String IAM_AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/iam/aws4_request";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static ValidatableResponse call(String action, String body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonDAXV3." + action)
                .header("Authorization", AUTH)
                .body(body).post("/").then();
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
    }

    private static List<String> defaultSubnets() {
        XmlPath xml = given().header("Authorization", EC2_AUTH).formParam("Action", "DescribeSubnets")
                .formParam("Filter.1.Name", "default-for-az").formParam("Filter.1.Value.1", "true")
                .post("/").then().statusCode(200).extract().xmlPath();
        List<String> subnets = xml.getList("DescribeSubnetsResponse.subnetSet.item.subnetId");
        assertTrue(subnets.size() >= 2, subnets.toString());
        return subnets;
    }

    private static String role(String trustedService) {
        String name = unique("dax-role-");
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":"
                + "{\"Service\":\"" + trustedService + "\"},\"Action\":\"sts:AssumeRole\"}]}";
        return given().header("Authorization", IAM_AUTH).formParam("Action", "CreateRole")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    private static String subnetGroup(List<String> subnets) {
        String name = unique("dax-subnets-");
        call("CreateSubnetGroup", "{\"SubnetGroupName\":\"" + name + "\",\"SubnetIds\":[\"" + subnets.get(0)
                + "\",\"" + subnets.get(1) + "\"]}").statusCode(200);
        return name;
    }

    @Test
    void subnetGroups() {
        List<String> subnets = defaultSubnets();
        String name = unique("Subnets-");
        call("CreateSubnetGroup", "{\"SubnetGroupName\":\"" + name + "\",\"Description\":\"first\","
                + "\"SubnetIds\":[\"" + subnets.get(0) + "\"]}").statusCode(200)
                .body("SubnetGroup.SubnetGroupName", equalTo(name.toLowerCase()))
                .body("SubnetGroup.VpcId", startsWith("vpc-"))
                .body("SubnetGroup.Subnets[0].SubnetIdentifier", equalTo(subnets.get(0)))
                .body("SubnetGroup.SupportedNetworkTypes", hasItem("ipv4"));
        call("CreateSubnetGroup", "{\"SubnetGroupName\":\"" + name + "\",\"SubnetIds\":[\"" + subnets.get(0)
                + "\"]}").statusCode(400).body("__type", containsString("SubnetGroupAlreadyExistsFault"));
        call("CreateSubnetGroup", "{\"SubnetGroupName\":\"" + unique("bad-") + "\",\"SubnetIds\":"
                + "[\"subnet-00000000000000000\"]}").statusCode(400).body("__type", containsString("InvalidSubnet"));
        call("UpdateSubnetGroup", "{\"SubnetGroupName\":\"" + name + "\",\"Description\":\"second\","
                + "\"SubnetIds\":[\"" + subnets.get(0) + "\",\"" + subnets.get(1) + "\"]}").statusCode(200)
                .body("SubnetGroup.Subnets", hasSize(2)).body("SubnetGroup.Description", equalTo("second"));
        call("DescribeSubnetGroups", "{\"SubnetGroupNames\":[\"" + name + "\"]}").statusCode(200)
                .body("SubnetGroups[0].Description", equalTo("second"));
        call("DeleteSubnetGroup", "{\"SubnetGroupName\":\"" + name + "\"}").statusCode(200)
                .body("DeletionMessage", containsString("deleted"));
        call("DescribeSubnetGroups", "{\"SubnetGroupNames\":[\"" + name + "\"]}").statusCode(404)
                .body("__type", containsString("SubnetGroupNotFoundFault"));
    }

    @Test
    void parameterGroups() {
        call("DescribeDefaultParameters", "{}").statusCode(200)
                .body("Parameters.ParameterName", equalTo(List.of("query-ttl-millis", "record-ttl-millis")))
                .body("Parameters[0].ParameterValue", equalTo("300000"));
        call("DescribeParameterGroups", "{}").statusCode(200)
                .body("ParameterGroups.ParameterGroupName", hasItem("default.dax1.0"));
        call("UpdateParameterGroup", "{\"ParameterGroupName\":\"default.dax1.0\",\"ParameterNameValues\":"
                + "[{\"ParameterName\":\"query-ttl-millis\",\"ParameterValue\":\"1\"}]}").statusCode(400)
                .body("__type", containsString("InvalidParameterGroupStateFault"));

        String name = unique("params-");
        call("CreateParameterGroup", "{\"ParameterGroupName\":\"" + name + "\",\"Description\":\"ttl\"}")
                .statusCode(200).body("ParameterGroup.Description", equalTo("ttl"));
        call("UpdateParameterGroup", "{\"ParameterGroupName\":\"" + name + "\",\"ParameterNameValues\":"
                + "[{\"ParameterName\":\"query-ttl-millis\",\"ParameterValue\":\"60000\"}]}").statusCode(200);
        call("DescribeParameters", "{\"ParameterGroupName\":\"" + name + "\",\"Source\":\"user\"}")
                .statusCode(200).body("Parameters", hasSize(1))
                .body("Parameters[0].ParameterValue", equalTo("60000"))
                .body("Parameters[0].ChangeType", equalTo("REQUIRES_REBOOT"));
        call("UpdateParameterGroup", "{\"ParameterGroupName\":\"" + name + "\",\"ParameterNameValues\":"
                + "[{\"ParameterName\":\"item-ttl\",\"ParameterValue\":\"1\"}]}").statusCode(400)
                .body("__type", containsString("InvalidParameterValueException"));
        call("UpdateParameterGroup", "{\"ParameterGroupName\":\"" + name + "\",\"ParameterNameValues\":"
                + "[{\"ParameterName\":\"record-ttl-millis\",\"ParameterValue\":\"soon\"}]}").statusCode(400);
        call("DeleteParameterGroup", "{\"ParameterGroupName\":\"" + name + "\"}").statusCode(200);
        call("DescribeParameters", "{\"ParameterGroupName\":\"" + name + "\"}").statusCode(404)
                .body("__type", containsString("ParameterGroupNotFoundFault"));
    }

    @Test
    void clusterLifecycle() {
        List<String> subnets = defaultSubnets();
        String subnetGroup = subnetGroup(subnets);
        String role = role("dax.amazonaws.com");
        String parameters = unique("cluster-params-");
        call("CreateParameterGroup", "{\"ParameterGroupName\":\"" + parameters + "\"}").statusCode(200);
        String name = unique("cache-");

        call("CreateCluster", "{\"ClusterName\":\"" + name + "\",\"NodeType\":\"dax.t3.small\","
                + "\"ReplicationFactor\":1,\"IamRoleArn\":\"" + role("lambda.amazonaws.com") + "\"}")
                .statusCode(400).body("message", containsString("No permission to assume role"));
        call("CreateCluster", "{\"ClusterName\":\"" + name + "\",\"NodeType\":\"dax.m9.huge\","
                + "\"ReplicationFactor\":1,\"IamRoleArn\":\"" + role + "\"}").statusCode(400)
                .body("__type", containsString("InvalidParameterValueException"));

        call("CreateCluster", "{\"ClusterName\":\"" + name + "\",\"NodeType\":\"dax.t3.small\","
                + "\"ReplicationFactor\":2,\"IamRoleArn\":\"" + role + "\",\"SubnetGroupName\":\"" + subnetGroup
                + "\",\"ParameterGroupName\":\"" + parameters + "\",\"SSESpecification\":{\"Enabled\":true},"
                + "\"Tags\":[{\"Key\":\"team\",\"Value\":\"cache\"}]}").statusCode(200)
                .body("Cluster.Status", equalTo("creating"))
                .body("Cluster.ClusterArn", equalTo("arn:aws:dax:us-east-1:000000000000:cache/" + name))
                .body("Cluster.TotalNodes", equalTo(2))
                .body("Cluster.ClusterDiscoveryEndpoint.Port", equalTo(8111))
                .body("Cluster.ClusterDiscoveryEndpoint.URL", startsWith("dax://" + name + "."))
                .body("Cluster.SSEDescription.Status", equalTo("ENABLED"));
        call("CreateCluster", "{\"ClusterName\":\"" + name + "\",\"NodeType\":\"dax.t3.small\","
                + "\"ReplicationFactor\":1,\"IamRoleArn\":\"" + role + "\",\"SubnetGroupName\":\"" + subnetGroup
                + "\"}").statusCode(400).body("__type", containsString("ClusterAlreadyExistsFault"));

        call("DescribeClusters", "{\"ClusterNames\":[\"" + name + "\"]}").statusCode(200)
                .body("Clusters[0].Status", equalTo("available"))
                .body("Clusters[0].ActiveNodes", equalTo(2))
                .body("Clusters[0].Nodes.NodeId", equalTo(List.of(name + "-a", name + "-b")))
                .body("Clusters[0].Nodes[0].Endpoint.Address", startsWith(name + "-a."))
                .body("Clusters[0].Nodes.AvailabilityZone", hasSize(2))
                .body("Clusters[0].SecurityGroups[0].Status", equalTo("active"))
                .body("Clusters[0].ParameterGroup.ParameterApplyStatus", equalTo("in-sync"));

        call("DeleteSubnetGroup", "{\"SubnetGroupName\":\"" + subnetGroup + "\"}").statusCode(400)
                .body("__type", containsString("SubnetGroupInUseFault"));
        call("DeleteParameterGroup", "{\"ParameterGroupName\":\"" + parameters + "\"}").statusCode(400)
                .body("__type", containsString("InvalidParameterGroupStateFault"));

        // A parameter change waits for each node's reboot.
        call("UpdateParameterGroup", "{\"ParameterGroupName\":\"" + parameters + "\",\"ParameterNameValues\":"
                + "[{\"ParameterName\":\"record-ttl-millis\",\"ParameterValue\":\"1000\"}]}").statusCode(200);
        call("DescribeClusters", "{\"ClusterNames\":[\"" + name + "\"]}").statusCode(200)
                .body("Clusters[0].ParameterGroup.ParameterApplyStatus", equalTo("pending-reboot"))
                .body("Clusters[0].ParameterGroup.NodeIdsToReboot", hasSize(2));
        call("RebootNode", "{\"ClusterName\":\"" + name + "\",\"NodeId\":\"" + name + "-a\"}").statusCode(200)
                .body("Cluster.Nodes.find { it.NodeId == '" + name + "-a' }.NodeStatus", equalTo("rebooting"));
        call("RebootNode", "{\"ClusterName\":\"" + name + "\",\"NodeId\":\"" + name + "-z\"}").statusCode(404)
                .body("__type", containsString("NodeNotFoundFault"));
        call("DescribeClusters", "{\"ClusterNames\":[\"" + name + "\"]}").statusCode(200)
                .body("Clusters[0].ParameterGroup.NodeIdsToReboot", equalTo(List.of(name + "-b")));

        // Resizing.
        call("IncreaseReplicationFactor", "{\"ClusterName\":\"" + name + "\",\"NewReplicationFactor\":2}")
                .statusCode(400).body("__type", containsString("InvalidParameterValueException"));
        call("IncreaseReplicationFactor", "{\"ClusterName\":\"" + name + "\",\"NewReplicationFactor\":3}")
                .statusCode(200).body("Cluster.Status", equalTo("modifying")).body("Cluster.TotalNodes", equalTo(3));
        call("DescribeClusters", "{\"ClusterNames\":[\"" + name + "\"]}").statusCode(200)
                .body("Clusters[0].Status", equalTo("available")).body("Clusters[0].ActiveNodes", equalTo(3));
        call("DecreaseReplicationFactor", "{\"ClusterName\":\"" + name + "\",\"NewReplicationFactor\":0}")
                .statusCode(400).body("__type", containsString("InvalidParameterValueException"));
        call("DecreaseReplicationFactor", "{\"ClusterName\":\"" + name + "\",\"NewReplicationFactor\":2,"
                + "\"NodeIdsToRemove\":[\"" + name + "-a\"]}").statusCode(200)
                .body("Cluster.NodeIdsToRemove", equalTo(List.of(name + "-a")))
                .body("Cluster.TotalNodes", equalTo(2));
        call("DescribeClusters", "{\"ClusterNames\":[\"" + name + "\"]}").statusCode(200)
                .body("Clusters[0].Nodes.NodeId", equalTo(List.of(name + "-b", name + "-c")));

        // Updates and tags.
        call("UpdateCluster", "{\"ClusterName\":\"" + name + "\",\"Description\":\"cache tier\","
                + "\"PreferredMaintenanceWindow\":\"tue:03:00-tue:04:00\"}").statusCode(200)
                .body("Cluster.Description", equalTo("cache tier"))
                .body("Cluster.PreferredMaintenanceWindow", equalTo("tue:03:00-tue:04:00"));
        call("UpdateCluster", "{\"ClusterName\":\"" + name + "\",\"PreferredMaintenanceWindow\":"
                + "\"tue:03:00-tue:03:30\"}").statusCode(400).body("message", containsString("60 minutes"));
        String arn = "arn:aws:dax:us-east-1:000000000000:cache/" + name;
        call("TagResource", "{\"ResourceName\":\"" + arn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
                .statusCode(200).body("Tags.Key", equalTo(List.of("team", "env")));
        call("UntagResource", "{\"ResourceName\":\"" + arn + "\",\"TagKeys\":[\"missing\"]}").statusCode(404)
                .body("__type", containsString("TagNotFoundFault"));
        call("UntagResource", "{\"ResourceName\":\"" + arn + "\",\"TagKeys\":[\"team\"]}").statusCode(200)
                .body("Tags.Key", equalTo(List.of("env")));
        call("ListTags", "{\"ResourceName\":\"not-an-arn\"}").statusCode(400)
                .body("__type", containsString("InvalidARNFault"));

        call("DescribeEvents", "{\"SourceName\":\"" + name + "\",\"SourceType\":\"CLUSTER\"}").statusCode(200)
                .body("Events.Message", hasItem("Cluster created"))
                .body("Events.Message", hasItem("Rebooted node " + name + "-a"))
                .body("Events.Message", hasItem("Removed node(s) " + name + "-a"));

        call("DeleteCluster", "{\"ClusterName\":\"" + name + "\"}").statusCode(200)
                .body("Cluster.Status", equalTo("deleting"));
        call("DescribeClusters", "{\"ClusterNames\":[\"" + name + "\"]}").statusCode(404)
                .body("__type", containsString("ClusterNotFoundFault"));
        call("DeleteSubnetGroup", "{\"SubnetGroupName\":\"" + subnetGroup + "\"}").statusCode(200);
        call("DeleteParameterGroup", "{\"ParameterGroupName\":\"" + parameters + "\"}").statusCode(200);
    }

    @Test
    void clusterWithoutSubnetGroupUsesTheDefaultVpc() {
        String role = role("dax.amazonaws.com");
        String name = unique("tls-");
        call("CreateCluster", "{\"ClusterName\":\"" + name + "\",\"NodeType\":\"dax.r5.large\","
                + "\"ReplicationFactor\":1,\"IamRoleArn\":\"" + role + "\",\"ClusterEndpointEncryptionType\":"
                + "\"TLS\"}").statusCode(200)
                .body("Cluster.SubnetGroup", equalTo("default"))
                .body("Cluster.ClusterDiscoveryEndpoint.Port", equalTo(9111))
                .body("Cluster.ClusterDiscoveryEndpoint.URL", startsWith("daxs://"))
                .body("Cluster.ClusterDiscoveryEndpoint.URL", endsWith(":9111"));
        call("DescribeSubnetGroups", "{\"SubnetGroupNames\":[\"default\"]}").statusCode(200)
                .body("SubnetGroups[0].Subnets", not(hasSize(0)));
        call("DescribeClusters", "{}").statusCode(200).body("Clusters.ClusterName", hasItem(name));
        call("DescribeClusters", "{\"MaxResults\":5}").statusCode(400);
        call("DeleteCluster", "{\"ClusterName\":\"" + name + "\"}").statusCode(200);
        call("DeleteCluster", "{\"ClusterName\":\"" + name + "\"}").statusCode(404);
    }
}
