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
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class RdsSubnetGroupDescriptionIntegrationTest {

    @Inject
    Ec2Service ec2;

    @Test
    void subnetGroupDescriptionUpdatesAndSurvivesOmission() {
        String name = "description-update-contract";
        List<String> subnets = ec2.describeSubnets("us-east-1", List.of(), Map.of()).stream()
                .filter(subnet -> ec2.resolveDefaultVpcId("us-east-1").equals(subnet.getVpcId()))
                .map(Subnet::getSubnetId).sorted().limit(2).toList();
        query("CreateDBSubnetGroup", name)
                .formParam("DBSubnetGroupDescription", "initial description")
                .formParam("SubnetIds.member.1", subnets.get(0))
                .formParam("SubnetIds.member.2", subnets.get(1))
                .post("/").then().statusCode(200);
        try {
            String changed = query("ModifyDBSubnetGroup", name)
                    .formParam("DBSubnetGroupDescription", "updated <description>")
                    .formParam("SubnetIds.member.1", subnets.get(0))
                    .formParam("SubnetIds.member.2", subnets.get(1))
                    .post("/").then().statusCode(200).extract().asString();
            assertEquals("updated <description>",
                    XmlParser.extractFirst(changed, "DBSubnetGroupDescription", null));
            query("ModifyDBSubnetGroup", name)
                    .formParam("SubnetIds.member.1", subnets.get(0))
                    .formParam("SubnetIds.member.2", subnets.get(1))
                    .post("/").then().statusCode(200);
            String observed = query("DescribeDBSubnetGroups", name)
                    .post("/").then().statusCode(200).extract().asString();
            assertEquals("updated <description>",
                    XmlParser.extractFirst(observed, "DBSubnetGroupDescription", null));
        } finally {
            query("DeleteDBSubnetGroup", name).post("/").then().statusCode(200);
        }
    }

    private static RequestSpecification query(String action, String name) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260921/us-east-1/"
                        + "rds/aws4_request, SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC).formParam("Action", action).formParam("Version", "2014-10-31")
                .formParam("DBSubnetGroupName", name);
    }
}
