package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB proxy endpoints over the Query protocol: create, describe after the proxy's default endpoint,
 * tag, rename, delete, and removal with their proxy. Runs in its own Region so the endpoint names
 * it uses are its own.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsProxyEndpointIntegrationTest {

    private static final String REGION = "eu-west-3";
    private static final String PROXY = "pep-proxy";
    private static final String MEMBERS =
            "DescribeDBProxyEndpointsResponse.DescribeDBProxyEndpointsResult.DBProxyEndpoints.member";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261019/" + REGION + "/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static String endpointArn;

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static RequestSpecification withSubnets(RequestSpecification request) {
        return request.formParam("VpcSubnetIds.member.1", Ec2Service.defaultSubnetId(REGION, "a"))
                .formParam("VpcSubnetIds.member.2", Ec2Service.defaultSubnetId(REGION, "b"));
    }

    private static RequestSpecification describe() {
        return query("DescribeDBProxyEndpoints").formParam("DBProxyName", PROXY);
    }

    @Test
    @Order(1)
    void anEndpointIsAddedToAProxy() {
        withSubnets(query("CreateDBProxy"))
                .formParam("DBProxyName", PROXY)
                .formParam("EngineFamily", "POSTGRESQL")
                .formParam("RoleArn", "arn:aws:iam::000000000000:role/proxy")
                .formParam("VpcSecurityGroupIds.member.1", "sg-proxy")
                .formParam("Auth.member.1.AuthScheme", "SECRETS")
                .formParam("Auth.member.1.SecretArn",
                        "arn:aws:secretsmanager:" + REGION + ":000000000000:secret:db-AbCdEf")
        .when().post("/").then().statusCode(200);

        endpointArn = withSubnets(query("CreateDBProxyEndpoint"))
                .formParam("DBProxyName", PROXY)
                .formParam("DBProxyEndpointName", "reader")
                .formParam("TargetRole", "READ_ONLY")
                .formParam("VpcSecurityGroupIds.member.1", "sg-endpoint")
                .formParam("Tags.Tag.1.Key", "team")
                .formParam("Tags.Tag.1.Value", "data")
        .when().post("/").then()
                .statusCode(200)
                .body(containsString("<CreateDBProxyEndpointResult><DBProxyEndpoint>"))
                .body("CreateDBProxyEndpointResponse.CreateDBProxyEndpointResult.DBProxyEndpoint.DBProxyEndpointName",
                        equalTo("reader"))
                .body(containsString("<DBProxyName>" + PROXY + "</DBProxyName>"))
                .body(containsString("<Status>available</Status>"))
                .body(containsString("<TargetRole>READ_ONLY</TargetRole>"))
                .body(containsString("<IsDefault>false</IsDefault>"))
                .body(containsString("<EndpointNetworkType>IPV4</EndpointNetworkType>"))
                .body(containsString("<VpcSecurityGroupIds><member>sg-endpoint</member></VpcSecurityGroupIds>"))
                .body(containsString("<CreatedDate>"))
                .extract().path("CreateDBProxyEndpointResponse.CreateDBProxyEndpointResult.DBProxyEndpoint"
                        + ".DBProxyEndpointArn");
        assertTrue(
                endpointArn.startsWith("arn:aws:rds:" + REGION + ":000000000000:db-proxy-endpoint:prx-endpoint-"),
                endpointArn);
    }

    @Test
    @Order(2)
    void theProxysDefaultEndpointIsListedFirst() {
        String proxyHost = query("DescribeDBProxies").formParam("DBProxyName", PROXY)
                .when().post("/").then().statusCode(200)
                .extract().path("DescribeDBProxiesResponse.DescribeDBProxiesResult.DBProxies.member.Endpoint");

        describe().when().post("/").then()
                .statusCode(200)
                .body(MEMBERS + ".size()", equalTo(2))
                .body(MEMBERS + "[0].DBProxyEndpointName", equalTo("default"))
                .body(MEMBERS + "[0].IsDefault", equalTo("true"))
                .body(MEMBERS + "[0].TargetRole", equalTo("READ_WRITE"))
                // Only the added endpoint has an ARN.
                .body(MEMBERS + ".DBProxyEndpointArn.size()", equalTo(1))
                .body(MEMBERS + "[1].DBProxyEndpointArn", equalTo(endpointArn))
                .body(MEMBERS + "[0].Endpoint", equalTo(proxyHost))
                .body(MEMBERS + "[1].DBProxyEndpointName", equalTo("reader"))
                .body(MEMBERS + "[1].Endpoint", equalTo(proxyHost))
                .body(not(containsString("<Marker>")));

        query("DescribeDBProxyEndpoints").formParam("DBProxyEndpointName", "absent")
                .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBProxyEndpointNotFoundFault</Code>"));
        query("DescribeDBProxyEndpoints").formParam("DBProxyName", "absent-proxy")
                .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBProxyNotFoundFault</Code>"));
    }

    @Test
    @Order(3)
    void anEndpointIsTaggedByItsArn() {
        query("ListTagsForResource").formParam("ResourceName", endpointArn)
                .when().post("/").then().statusCode(200)
                .body(containsString("<Key>team</Key><Value>data</Value>"));
    }

    @Test
    @Order(4)
    void requestsOutsideTheModelAreRefused() {
        withSubnets(query("CreateDBProxyEndpoint"))
                .formParam("DBProxyName", PROXY).formParam("DBProxyEndpointName", "reader")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>DBProxyEndpointAlreadyExistsFault</Code>"));
        withSubnets(query("CreateDBProxyEndpoint"))
                .formParam("DBProxyName", PROXY).formParam("DBProxyEndpointName", "bad--name")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        withSubnets(query("CreateDBProxyEndpoint"))
                .formParam("DBProxyName", PROXY).formParam("DBProxyEndpointName", "writer")
                .formParam("TargetRole", "WRITER")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        query("CreateDBProxyEndpoint")
                .formParam("DBProxyName", PROXY).formParam("DBProxyEndpointName", "lonely")
                .formParam("VpcSubnetIds.member.1", Ec2Service.defaultSubnetId(REGION, "a"))
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidSubnet</Code>"));
        withSubnets(query("CreateDBProxyEndpoint")).formParam("DBProxyEndpointName", "orphan")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>MissingParameter</Code>"))
                .body(containsString("The request must contain the parameter DBProxyName."));
        withSubnets(query("CreateDBProxyEndpoint")).formParam("DBProxyName", PROXY)
                .when().post("/").then().statusCode(400)
                .body(containsString("The request must contain the parameter DBProxyEndpointName."));
        query("CreateDBProxyEndpoint")
                .formParam("DBProxyName", PROXY).formParam("DBProxyEndpointName", "nowhere")
                .when().post("/").then().statusCode(400)
                .body(containsString("The request must contain the parameter VpcSubnetIds."));
        query("DeleteDBProxyEndpoint")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>MissingParameter</Code>"));
        query("ModifyDBProxyEndpoint").formParam("DBProxyEndpointName", "default")
                .formParam("NewDBProxyEndpointName", "renamed")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidDBProxyEndpointStateFault</Code>"));
        query("DeleteDBProxyEndpoint").formParam("DBProxyEndpointName", "default")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidDBProxyEndpointStateFault</Code>"));
        describe().formParam("MaxRecords", "19")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    @Order(5)
    void anEndpointIsRenamedAndDeleted() {
        query("ModifyDBProxyEndpoint")
                .formParam("DBProxyEndpointName", "reader")
                .formParam("NewDBProxyEndpointName", "analytics")
                .formParam("VpcSecurityGroupIds.member.1", "sg-other")
        .when().post("/").then()
                .statusCode(200)
                .body(containsString("<ModifyDBProxyEndpointResult><DBProxyEndpoint>"))
                .body(containsString("<DBProxyEndpointName>analytics</DBProxyEndpointName>"))
                .body(containsString("<DBProxyEndpointArn>" + endpointArn + "</DBProxyEndpointArn>"))
                .body(containsString("<VpcSecurityGroupIds><member>sg-other</member></VpcSecurityGroupIds>"));

        query("DeleteDBProxyEndpoint").formParam("DBProxyEndpointName", "analytics")
                .when().post("/").then()
                .statusCode(200)
                .body(containsString("<DeleteDBProxyEndpointResult><DBProxyEndpoint>"))
                .body(containsString("<Status>deleting</Status>"));
        query("DeleteDBProxyEndpoint").formParam("DBProxyEndpointName", "analytics")
                .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBProxyEndpointNotFoundFault</Code>"));
        describe().when().post("/").then().statusCode(200)
                .body(MEMBERS + ".DBProxyEndpointName", equalTo("default"));
    }

    @Test
    @Order(6)
    void deletingTheProxyDeletesItsEndpoints() {
        withSubnets(query("CreateDBProxyEndpoint"))
                .formParam("DBProxyName", PROXY).formParam("DBProxyEndpointName", "leftover")
                .when().post("/").then().statusCode(200);

        query("DeleteDBProxy").formParam("DBProxyName", PROXY).when().post("/").then().statusCode(200);

        query("DeleteDBProxyEndpoint").formParam("DBProxyEndpointName", "leftover")
                .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBProxyEndpointNotFoundFault</Code>"));
        query("DescribeDBProxyEndpoints").when().post("/").then().statusCode(200)
                .body(not(containsString("<member>")));
    }
}
