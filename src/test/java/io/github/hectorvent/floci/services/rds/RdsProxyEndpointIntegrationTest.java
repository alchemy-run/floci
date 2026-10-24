package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RdsProxyEndpointIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260921/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    @Inject
    Ec2Service ec2;

    @Test
    void endpointQueryLifecycleIncludesDefaultAndPreservesArnAcrossRename() {
        String proxy = "endpoint-wire-parent";
        createProxy(proxy);
        try {
            String initial = query("DescribeDBProxyEndpoints").formParam("DBProxyName", proxy)
                    .post("/").then().statusCode(200).extract().asString();
            assertEquals(List.of("member"), XmlParser.childElementNames(initial, "DBProxyEndpoints"));
            assertEquals(List.of(proxy), XmlParser.extractAll(initial, "DBProxyEndpointName"));
            assertEquals("true", XmlParser.extractFirst(initial, "IsDefault", null));
            assertEquals("READ_WRITE", XmlParser.extractFirst(initial, "TargetRole", null));
            query("DeleteDBProxyEndpoint").formParam("DBProxyEndpointName", proxy)
                    .post("/").then().statusCode(400)
                    .body(containsString("<Code>InvalidDBProxyEndpointStateFault</Code>"));

            query("CreateDBProxyEndpoint").formParam("DBProxyName", proxy)
                    .formParam("DBProxyEndpointName", "endpoint-wire-invalid")
                    .formParam("VpcSubnetIds.member.1", "subnet-00000000000000000")
                    .post("/").then().statusCode(400).body(containsString("<Code>InvalidSubnet</Code>"));
            createEndpoint(proxy, "endpoint-wire-invalid")
                    .formParam("VpcSecurityGroupIds.member.1", "sg-00000000000000000")
                    .post("/").then().statusCode(400).body(containsString("<Code>InvalidParameterValue</Code>"));
            String created = createEndpoint(proxy, "endpoint-wire-reader")
                    .formParam("TargetRole", "READ_ONLY")
                    .formParam("EndpointNetworkType", "IPV4")
                    .formParam("Tags.Tag.1.Key", "owner").formParam("Tags.Tag.1.Value", "wire<&")
                    .post("/").then().statusCode(200).extract().asString();
            String arn = XmlParser.extractFirst(created, "DBProxyEndpointArn", null);
            assertNotNull(arn);
            assertTrue(arn.startsWith("arn:aws:rds:us-east-1:000000000000:db-proxy-endpoint:prx-endpoint-"));
            assertEquals("false", XmlParser.extractFirst(created, "IsDefault", null));
            assertEquals("READ_ONLY", XmlParser.extractFirst(created, "TargetRole", null));
            assertEquals("IPV4", XmlParser.extractFirst(created, "EndpointNetworkType", null));
            assertEquals(subnetIds(), XmlParser.extractGroupsMulti(created, "VpcSubnetIds").getFirst().get("member"));
            assertNotNull(XmlParser.extractFirst(created, "CreatedDate", null));
            query("ListTagsForResource").formParam("ResourceName", arn)
                    .post("/").then().statusCode(200).body(containsString("wire&lt;&amp;"));
            createEndpoint(proxy, "endpoint-wire-reader").post("/").then().statusCode(400)
                    .body(containsString("<Code>DBProxyEndpointAlreadyExistsFault</Code>"));

            String changed = query("ModifyDBProxyEndpoint")
                    .formParam("DBProxyEndpointName", "endpoint-wire-reader")
                    .formParam("NewDBProxyEndpointName", "endpoint-wire-renamed")
                    .formParam("VpcSecurityGroupIds.member.1", ec2.resolveDefaultSecurityGroupId(REGION))
                    .post("/").then().statusCode(200).extract().asString();
            assertEquals(arn, XmlParser.extractFirst(changed, "DBProxyEndpointArn", null));
            assertEquals(XmlParser.extractFirst(created, "Endpoint", null),
                    XmlParser.extractFirst(changed, "Endpoint", null));
            assertEquals("endpoint-wire-renamed", XmlParser.extractFirst(changed, "DBProxyEndpointName", null));
            query("AddTagsToResource").formParam("ResourceName", arn)
                    .formParam("Tags.member.1.Key", "phase").formParam("Tags.member.1.Value", "updated")
                    .post("/").then().statusCode(200);
            query("RemoveTagsFromResource").formParam("ResourceName", arn)
                    .formParam("TagKeys.member.1", "owner").post("/").then().statusCode(200);
            String tags = query("ListTagsForResource").formParam("ResourceName", arn)
                    .post("/").then().statusCode(200).extract().asString();
            assertEquals(List.of("phase"), XmlParser.extractAll(tags, "Key"));
            query("DescribeDBProxyEndpoints").formParam("DBProxyName", proxy)
                    .formParam("DBProxyEndpointName", "endpoint-wire-reader")
                    .post("/").then().statusCode(404)
                    .body(containsString("<Code>DBProxyEndpointNotFoundFault</Code>"));
            query("DeleteDBProxyEndpoint").formParam("DBProxyEndpointName", "endpoint-wire-renamed")
                    .post("/").then().statusCode(200).body(containsString("<Status>deleting</Status>"));
            query("ListTagsForResource").formParam("ResourceName", arn)
                    .post("/").then().statusCode(404)
                    .body(containsString("<Code>DBProxyEndpointNotFoundFault</Code>"));
        } finally {
            query("DeleteDBProxy").formParam("DBProxyName", proxy).post("/").then().statusCode(200);
        }
    }

    @Test
    void endpointsPaginateEnforceQuotaAndDisappearWithTheirParent() {
        String proxy = "endpoint-page-parent";
        createProxy(proxy);
        try {
            for (int index = 0; index < 20; index++) {
                createEndpoint(proxy, "endpoint-page-" + index).post("/").then().statusCode(200);
            }
            createEndpoint(proxy, "endpoint-page-excess").post("/").then().statusCode(400)
                    .body(containsString("<Code>DBProxyEndpointQuotaExceededFault</Code>"));
            String first = query("DescribeDBProxyEndpoints").formParam("DBProxyName", proxy)
                    .formParam("MaxRecords", "20").post("/").then().statusCode(200).extract().asString();
            List<String> names = XmlParser.extractAll(first, "DBProxyEndpointName");
            assertEquals(20, names.size());
            String marker = XmlParser.extractFirst(first, "Marker", null);
            assertNotNull(marker);
            String last = query("DescribeDBProxyEndpoints").formParam("DBProxyName", proxy)
                    .formParam("MaxRecords", "20").formParam("Marker", marker)
                    .post("/").then().statusCode(200).extract().asString();
            List<String> lastNames = XmlParser.extractAll(last, "DBProxyEndpointName");
            assertEquals(1, lastNames.size());
            assertFalse(names.contains(lastNames.getFirst()));
            assertFalse(last.contains("<Marker>"));
            query("DescribeDBProxyEndpoints").formParam("DBProxyName", proxy).formParam("Marker", "invalid")
                    .post("/").then().statusCode(400).body(containsString("<Code>InvalidParameterValue</Code>"));
            query("DescribeDBProxyEndpoints").formParam("DBProxyName", proxy).formParam("MaxRecords", "19")
                    .post("/").then().statusCode(400).body(containsString("<Code>InvalidParameterValue</Code>"));
        } finally {
            query("DeleteDBProxy").formParam("DBProxyName", proxy).post("/").then().statusCode(200);
        }
        query("DescribeDBProxyEndpoints").formParam("DBProxyEndpointName", "endpoint-page-0")
                .post("/").then().statusCode(404).body(containsString("<Code>DBProxyEndpointNotFoundFault</Code>"));
        query("DescribeDBProxyEndpoints").formParam("DBProxyName", proxy)
                .post("/").then().statusCode(404).body(containsString("<Code>DBProxyNotFoundFault</Code>"));
        createEndpoint(proxy, "endpoint-page-orphan").post("/").then().statusCode(404)
                .body(containsString("<Code>DBProxyNotFoundFault</Code>"));
    }

    private void createProxy(String name) {
        List<String> subnets = subnetIds();
        query("CreateDBProxy").formParam("DBProxyName", name).formParam("EngineFamily", "POSTGRESQL")
                .formParam("RoleArn", "arn:aws:iam::000000000000:role/endpoint-wire")
                .formParam("DefaultAuthScheme", "IAM_AUTH")
                .formParam("VpcSubnetIds.member.1", subnets.get(0))
                .formParam("VpcSubnetIds.member.2", subnets.get(1))
                .post("/").then().statusCode(200);
    }

    private RequestSpecification createEndpoint(String proxy, String name) {
        List<String> subnets = subnetIds();
        return query("CreateDBProxyEndpoint").formParam("DBProxyName", proxy)
                .formParam("DBProxyEndpointName", name)
                .formParam("VpcSubnetIds.member.1", subnets.get(0))
                .formParam("VpcSubnetIds.member.2", subnets.get(1));
    }

    private List<String> subnetIds() {
        return ec2.describeSubnets(REGION, List.of(), Map.of()).stream()
                .filter(subnet -> ec2.resolveDefaultVpcId(REGION).equals(subnet.getVpcId()))
                // Other classes add subnets to the default VPC; a proxy needs two distinct zones.
                .sorted(java.util.Comparator.comparing(Subnet::getSubnetId))
                .collect(java.util.stream.Collectors.toMap(Subnet::getAvailabilityZone, Subnet::getSubnetId,
                        (first, second) -> first, java.util.TreeMap::new))
                .values().stream().limit(2).toList();
    }

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH).contentType(URLENC)
                .formParam("Action", action).formParam("Version", "2014-10-31");
    }
}
