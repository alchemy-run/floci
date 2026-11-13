package io.floci.aws.services.directoryservice;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

/**
 * AWS Directory Service over JSON 1.1: Managed Microsoft AD directories placed in a VPC's subnets, their features,
 * AD Connector reaching a directory by its DNS addresses, trusts verified between directories, snapshots and
 * Simple AD closed to new customers.
 */
@QuarkusTest
class DirectoryServiceIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/ds/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/ec2/aws4_request";
    private static final String PASSWORD = "AlchemyTest123!";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse ds(String action, String body) {
        return given().header("Authorization", AUTH).header("X-Amz-Target", "DirectoryService_20150416." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then();
    }

    private static String ec2(String action, String path, String... params) {
        RequestSpecification request = given().header("Authorization", EC2_AUTH).formParam("Action", action)
                .formParam("Version", "2016-11-15");
        for (int index = 0; index < params.length; index += 2) {
            request = request.formParam(params[index], params[index + 1]);
        }
        return request.post("/").then().statusCode(200).extract().xmlPath().getString(path);
    }

    /** A VPC with two subnets in different Availability Zones. */
    private static String[] network(String octet) {
        String vpc = ec2("CreateVpc", "CreateVpcResponse.vpc.vpcId", "CidrBlock", "10." + octet + ".0.0/16");
        String a = ec2("CreateSubnet", "CreateSubnetResponse.subnet.subnetId", "VpcId", vpc, "CidrBlock",
                "10." + octet + ".1.0/24", "AvailabilityZone", "us-east-1a");
        String b = ec2("CreateSubnet", "CreateSubnetResponse.subnet.subnetId", "VpcId", vpc, "CidrBlock",
                "10." + octet + ".2.0/24", "AvailabilityZone", "us-east-1b");
        return new String[] {vpc, a, b};
    }

    private static String microsoftAd(String name, String[] network) {
        return ds("CreateMicrosoftAD", "{\"Name\":\"" + name + "\",\"Password\":\"" + PASSWORD + "\",\"Edition\":"
                + "\"Standard\",\"VpcSettings\":{\"VpcId\":\"" + network[0] + "\",\"SubnetIds\":[\"" + network[1]
                + "\",\"" + network[2] + "\"]},\"Tags\":[{\"Key\":\"team\",\"Value\":\"identity\"}]}")
                .statusCode(200).body("DirectoryId", startsWith("d-")).extract().path("DirectoryId");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    @Test
    void microsoftAdIsPlacedAndConfigured() {
        String[] network = network("61");
        ds("CreateMicrosoftAD", "{\"Name\":\"bad.example\",\"Password\":\"" + PASSWORD + "\",\"VpcSettings\":"
                + "{\"VpcId\":\"" + network[0] + "\",\"SubnetIds\":[\"" + network[1] + "\",\"" + network[1]
                + "\"]}}").statusCode(400).body("__type", equalTo("InvalidParameterException"));
        ds("CreateMicrosoftAD", "{\"Name\":\"weak.example\",\"Password\":\"weak\",\"VpcSettings\":{\"VpcId\":\""
                + network[0] + "\",\"SubnetIds\":[\"" + network[1] + "\",\"" + network[2] + "\"]}}")
                .statusCode(400).body("message", containsString("Value at 'Password' failed to satisfy constraint"));

        String name = "corp" + suffix() + ".example.internal";
        String id = microsoftAd(name, network);
        ValidatableResponse described = ds("DescribeDirectories", "{\"DirectoryIds\":[\"" + id + "\"]}")
                .statusCode(200).body("DirectoryDescriptions[0].Stage", equalTo("Active"))
                .body("DirectoryDescriptions[0].Type", equalTo("MicrosoftAD"))
                .body("DirectoryDescriptions[0].DnsIpAddrs", hasSize(2))
                .body("DirectoryDescriptions[0].VpcSettings.SecurityGroupId", startsWith("sg-"))
                .body("DirectoryDescriptions[0].VpcSettings.AvailabilityZones", hasItem("us-east-1b"));
        List<String> dns = described.extract().path("DirectoryDescriptions[0].DnsIpAddrs");
        assertThat(dns.get(0), startsWith("10.61.1."));
        ds("DescribeDomainControllers", "{\"DirectoryId\":\"" + id + "\"}").statusCode(200)
                .body("DomainControllers", hasSize(2)).body("DomainControllers[0].Status", equalTo("Active"));
        ds("ListTagsForResource", "{\"ResourceId\":\"" + id + "\"}").statusCode(200)
                .body("Tags[0].Key", equalTo("team"));

        ds("CreateConditionalForwarder", "{\"DirectoryId\":\"" + id + "\",\"RemoteDomainName\":\"partner.example\","
                + "\"DnsIpAddrs\":[\"10.200.0.2\"]}").statusCode(200);
        ds("UpdateConditionalForwarder", "{\"DirectoryId\":\"" + id + "\",\"RemoteDomainName\":\"partner.example\","
                + "\"DnsIpAddrs\":[\"10.200.0.2\",\"10.200.1.2\"]}").statusCode(200);
        ds("DescribeConditionalForwarders", "{\"DirectoryId\":\"" + id + "\"}").statusCode(200)
                .body("ConditionalForwarders[0].DnsIpAddrs", hasSize(2));
        ds("AddIpRoutes", "{\"DirectoryId\":\"" + id + "\",\"IpRoutes\":[{\"CidrIp\":\"172.16.0.0/16\"}]}")
                .statusCode(200);
        ds("ListIpRoutes", "{\"DirectoryId\":\"" + id + "\"}").statusCode(200)
                .body("IpRoutesInfo[0].IpRouteStatusMsg", equalTo("Added"));
        ds("ResetUserPassword", "{\"DirectoryId\":\"" + id + "\",\"UserName\":\"nobody\",\"NewPassword\":\""
                + PASSWORD + "\"}").statusCode(400).body("__type", equalTo("UserDoesNotExistException"));
        ds("ResetUserPassword", "{\"DirectoryId\":\"" + id + "\",\"UserName\":\"Admin\",\"NewPassword\":"
                + "\"NewPassword123!\"}").statusCode(200);
        ds("CreateComputer", "{\"DirectoryId\":\"" + id + "\",\"ComputerName\":\"WEB01\",\"Password\":\"Join12345\"}")
                .statusCode(200).body("Computer.ComputerId", startsWith("S-1-5-21-"));
        ds("UpdateNumberOfDomainControllers", "{\"DirectoryId\":\"" + id + "\",\"DesiredNumber\":3}")
                .statusCode(200);
        ds("DescribeDomainControllers", "{\"DirectoryId\":\"" + id + "\"}").statusCode(200)
                .body("DomainControllers", hasSize(3));
        ds("UpdateSettings", "{\"DirectoryId\":\"" + id + "\",\"Settings\":[{\"Name\":\"TLS_1_0\",\"Value\":"
                + "\"Disable\"}]}").statusCode(200);
        ds("DescribeSettings", "{\"DirectoryId\":\"" + id + "\",\"Status\":\"Updated\"}").statusCode(200)
                .body("SettingEntries[0].AppliedValue", equalTo("Disable"));
        ds("EnableLDAPS", "{\"DirectoryId\":\"" + id + "\",\"Type\":\"Client\"}").statusCode(400)
                .body("__type", equalTo("NoAvailableCertificateException"));
        ds("CreateAlias", "{\"DirectoryId\":\"" + id + "\",\"Alias\":\"corp-" + suffix() + "\"}").statusCode(200);
        ds("EnableSso", "{\"DirectoryId\":\"" + id + "\"}").statusCode(200);

        String snapshot = ds("CreateSnapshot", "{\"DirectoryId\":\"" + id + "\",\"Name\":\"before\"}")
                .statusCode(200).extract().path("SnapshotId");
        ds("DescribeSnapshots", "{\"DirectoryId\":\"" + id + "\"}").statusCode(200)
                .body("Snapshots.Type", hasItem("Auto")).body("Snapshots.SnapshotId", hasItem(snapshot));
        ds("DeleteConditionalForwarder", "{\"DirectoryId\":\"" + id + "\",\"RemoteDomainName\":\"partner.example\"}")
                .statusCode(200);
        ds("RestoreFromSnapshot", "{\"SnapshotId\":\"" + snapshot + "\"}").statusCode(200);
        ds("DescribeConditionalForwarders", "{\"DirectoryId\":\"" + id + "\"}").statusCode(200)
                .body("ConditionalForwarders", hasSize(1));

        ds("DeleteDirectory", "{\"DirectoryId\":\"" + id + "\"}").statusCode(200);
        ds("DescribeDirectories", "{\"DirectoryIds\":[\"" + id + "\"]}").statusCode(400)
                .body("__type", equalTo("EntityDoesNotExistException"));
    }

    @Test
    void connectorsAndTrustsReachTheirDomains() {
        String[] network = network("62");
        String name = "corp" + suffix() + ".example.internal";
        String id = microsoftAd(name, network);
        List<String> dns = ds("DescribeDirectories", "{\"DirectoryIds\":[\"" + id + "\"]}").extract()
                .path("DirectoryDescriptions[0].DnsIpAddrs");
        String connect = "{\"Name\":\"" + name + "\",\"Password\":\"PASSWORD\",\"Size\":\"Small\",\"ConnectSettings\":"
                + "{\"VpcId\":\"" + network[0] + "\",\"SubnetIds\":[\"" + network[1] + "\",\"" + network[2]
                + "\"],\"CustomerDnsIps\":[\"" + dns.get(0) + "\"],\"CustomerUserName\":\"Admin\"}}";
        String good = ds("ConnectDirectory", connect.replace("PASSWORD", PASSWORD)).statusCode(200).extract()
                .path("DirectoryId");
        ds("DescribeDirectories", "{\"DirectoryIds\":[\"" + good + "\"]}").statusCode(200)
                .body("DirectoryDescriptions[0].Stage", equalTo("Active"))
                .body("DirectoryDescriptions[0].ConnectSettings.ConnectIps", hasSize(2));
        String bad = ds("ConnectDirectory", connect.replace("PASSWORD", "WrongPassword1!")).statusCode(200)
                .extract().path("DirectoryId");
        ds("DescribeDirectories", "{\"DirectoryIds\":[\"" + bad + "\"]}").statusCode(200)
                .body("DirectoryDescriptions[0].Stage", equalTo("Failed"))
                .body("DirectoryDescriptions[0].StageReason", containsString("Authentication failed"));

        String partner = "partner" + suffix() + ".example.internal";
        String partnerId = microsoftAd(partner, network("63"));
        List<String> partnerDns = ds("DescribeDirectories", "{\"DirectoryIds\":[\"" + partnerId + "\"]}").extract()
                .path("DirectoryDescriptions[0].DnsIpAddrs");
        String unreachable = ds("CreateTrust", "{\"DirectoryId\":\"" + id + "\",\"RemoteDomainName\":\"nowhere.example"
                + "\",\"TrustPassword\":\"secret\",\"TrustDirection\":\"Two-Way\"}").statusCode(200).extract()
                .path("TrustId");
        String trust = ds("CreateTrust", "{\"DirectoryId\":\"" + id + "\",\"RemoteDomainName\":\"" + partner
                + "\",\"TrustPassword\":\"secret\",\"TrustDirection\":\"Two-Way\",\"ConditionalForwarderIpAddrs\":[\""
                + partnerDns.get(0) + "\"]}").statusCode(200).extract().path("TrustId");
        ds("DescribeTrusts", "{\"TrustIds\":[\"" + trust + "\",\"" + unreachable + "\"]}").statusCode(200)
                .body("Trusts.find { it.TrustId == '" + trust + "' }.TrustState", equalTo("Verified"))
                .body("Trusts.find { it.TrustId == '" + unreachable + "' }.TrustState", equalTo("VerifyFailed"));
        ds("DeleteTrust", "{\"TrustId\":\"" + trust + "\",\"DeleteAssociatedConditionalForwarder\":true}")
                .statusCode(200);
        ds("DescribeConditionalForwarders", "{\"DirectoryId\":\"" + id + "\"}").statusCode(200)
                .body("ConditionalForwarders", hasSize(0));
    }

    @Test
    void simpleAdIsClosedToNewCustomers() {
        ds("CreateDirectory", "{\"Name\":\"closed.example\",\"Password\":\"" + PASSWORD + "\",\"Size\":\"Small\","
                + "\"VpcSettings\":{\"VpcId\":\"vpc-00000000000000000\",\"SubnetIds\":[\"subnet-00000000000000000\",\"subnet-00000000000000001\"]}}")
                .statusCode(400).body("__type", equalTo("ClientException"))
                .body("message", containsString("no longer open to new customers"));
        ds("GetDirectoryLimits", "{}").statusCode(200).body("DirectoryLimits.CloudOnlyMicrosoftADLimit",
                equalTo(20));
    }
}
