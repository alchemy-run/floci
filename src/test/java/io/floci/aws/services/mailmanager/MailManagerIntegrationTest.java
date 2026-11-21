package io.floci.aws.services.mailmanager;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mail Manager: rule sets, traffic policies, relays, address lists and their import jobs, archives and add-ons; an
 * ingress point receiving mail over SMTP, its rules archiving and storing messages, and archive searches, exports
 * and message downloads.
 */
@QuarkusTest
class MailManagerIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static ValidatableResponse mm(String action, String body) {
        return given().header("Authorization", auth("ses")).header("X-Amz-Target", "MailManagerSvc." + action)
                .contentType("application/x-amz-json-1.0").body(body).post("/").then().log().ifValidationFails();
    }

    static String ruleSet(String name, String rules) {
        return mm("CreateRuleSet", "{\"RuleSetName\":\"" + name + "\",\"Rules\":" + rules + "}").statusCode(200)
                .extract().path("RuleSetId");
    }

    static String trafficPolicy(String name, String extra) {
        return mm("CreateTrafficPolicy", "{\"TrafficPolicyName\":\"" + name + "\",\"DefaultAction\":\"ALLOW\","
                + "\"PolicyStatements\":[" + extra + "]}").statusCode(200).extract().path("TrafficPolicyId");
    }

    static String ingress(String name, String ruleSet, String policy, String type, String config) {
        String id = mm("CreateIngressPoint", "{\"IngressPointName\":\"" + name + "\",\"Type\":\"" + type
                + "\",\"RuleSetId\":\"" + ruleSet + "\",\"TrafficPolicyId\":\"" + policy + "\"" + config + "}")
                .statusCode(200).extract().path("IngressPointId");
        await().atMost(Duration.ofSeconds(10)).until(() -> "ACTIVE".equals(mm("GetIngressPoint",
                "{\"IngressPointId\":\"" + id + "\"}").extract().path("Status")));
        return id;
    }

    static int port(String ingressPointId) {
        return mm("GetIngressPoint", "{\"IngressPointId\":\"" + ingressPointId + "\"}").statusCode(200)
                .extract().path("SmtpPort");
    }

    /** A small SMTP client: each command's last reply line. */
    static final class Smtp implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader in;
        private final OutputStream out;

        Smtp(int port) throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(10_000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        String read() throws IOException {
            String line;
            do {
                line = in.readLine();
            } while (line != null && line.length() > 3 && line.charAt(3) == '-');
            return line;
        }

        String send(String command) throws IOException {
            out.write((command + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return read();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    static String message(String from, String to, String subject, String body) {
        return "From: " + from + "\r\nTo: " + to + "\r\nSubject: " + subject + "\r\nMessage-ID: <" + subject
                + "@floci>\r\nContent-Type: text/plain\r\n\r\n" + body + "\r\n.hidden\r\n";
    }

    static List<String> deliver(int port, String from, List<String> to, String raw) throws IOException {
        List<String> replies = new ArrayList<>();
        try (Smtp smtp = new Smtp(port)) {
            replies.add(smtp.read());
            replies.add(smtp.send("EHLO client.example.com"));
            replies.add(smtp.send("MAIL FROM:<" + from + ">"));
            for (String recipient : to) {
                replies.add(smtp.send("RCPT TO:<" + recipient + ">"));
            }
            replies.add(smtp.send("DATA"));
            String stuffed = raw.replace("\r\n.", "\r\n..");
            replies.add(smtp.send(stuffed + "\r\n."));
            replies.add(smtp.send("QUIT"));
        }
        return replies;
    }

    @Test
    void ruleSetsAndTrafficPoliciesAreValidatedAndNamedUniquely() {
        String rs = ruleSet("validated", "[{\"Name\":\"drop\",\"Actions\":[{\"Drop\":{}}]}]");
        mm("CreateRuleSet", "{\"RuleSetName\":\"validated\",\"Rules\":[]}").statusCode(400)
                .body("__type", containsString("ConflictException"));
        mm("CreateRuleSet", "{\"RuleSetName\":\"empty-actions\",\"Rules\":[{\"Name\":\"x\",\"Actions\":[]}]}")
                .statusCode(400).body("__type", containsString("ValidationException"));
        mm("GetRuleSet", "{\"RuleSetId\":\"" + rs + "\"}").statusCode(200).body("RuleSetName", equalTo("validated"))
                .body("RuleSetArn", containsString(":ses:us-east-1:000000000000:mailmanager-rule-set/" + rs))
                .body("Rules[0].Name", equalTo("drop"));
        mm("UpdateRuleSet", "{\"RuleSetId\":\"" + rs + "\",\"RuleSetName\":\"renamed\"}").statusCode(200);
        mm("ListRuleSets", "{}").statusCode(200).body("RuleSets.RuleSetName", hasItem("renamed"));

        String tp = trafficPolicy("cidr", "{\"Action\":\"DENY\",\"Conditions\":[{\"IpExpression\":{\"Evaluate\":"
                + "{\"Attribute\":\"SENDER_IP\"},\"Operator\":\"CIDR_MATCHES\",\"Values\":[\"10.0.0.0/8\"]}}]}");
        mm("CreateTrafficPolicy", "{\"TrafficPolicyName\":\"bad-cidr\",\"DefaultAction\":\"ALLOW\","
                + "\"PolicyStatements\":[{\"Action\":\"DENY\",\"Conditions\":[{\"IpExpression\":{\"Evaluate\":"
                + "{\"Attribute\":\"SENDER_IP\"},\"Operator\":\"CIDR_MATCHES\",\"Values\":[\"10.0.0.0/99\"]}}]}]}")
                .statusCode(400).body("__type", containsString("ValidationException"));
        String ip = ingress("in-use", rs, tp, "OPEN", "");
        mm("DeleteRuleSet", "{\"RuleSetId\":\"" + rs + "\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));
        mm("DeleteIngressPoint", "{\"IngressPointId\":\"" + ip + "\"}").statusCode(200);
        mm("DeleteRuleSet", "{\"RuleSetId\":\"" + rs + "\"}").statusCode(200);
        mm("DeleteTrafficPolicy", "{\"TrafficPolicyId\":\"" + tp + "\"}").statusCode(200);
        mm("GetRuleSet", "{\"RuleSetId\":\"" + rs + "\"}").statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void addressListsTakeMembersAndImportJobs() {
        String list = mm("CreateAddressList", "{\"AddressListName\":\"allowed\",\"Tags\":[{\"Key\":\"team\","
                + "\"Value\":\"mail\"}]}").statusCode(200).extract().path("AddressListId");
        mm("RegisterMemberToAddressList", "{\"AddressListId\":\"" + list + "\",\"Address\":\"Ada@Example.com\"}")
                .statusCode(200);
        mm("GetMemberOfAddressList", "{\"AddressListId\":\"" + list + "\",\"Address\":\"ada@example.com\"}")
                .statusCode(200).body("Address", equalTo("ada@example.com"));
        mm("RegisterMemberToAddressList", "{\"AddressListId\":\"" + list + "\",\"Address\":\"not-an-address\"}")
                .statusCode(400).body("__type", containsString("ValidationException"));

        String job = mm("CreateAddressListImportJob", "{\"AddressListId\":\"" + list + "\",\"Name\":\"csv\","
                + "\"ImportDataFormat\":{\"ImportDataType\":\"CSV\"}}").statusCode(200).extract().path("JobId");
        String url = mm("GetAddressListImportJob", "{\"JobId\":\"" + job + "\"}").statusCode(200)
                .body("Status", equalTo("CREATED")).extract().path("PreSignedUrl");
        given().contentType("text/csv").body("address\ngrace@example.com\nlinus@example.com\n")
                .put(URI.create(url).getPath()).then().statusCode(200);
        mm("StartAddressListImportJob", "{\"JobId\":\"" + job + "\"}").statusCode(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETED".equals(mm("GetAddressListImportJob",
                "{\"JobId\":\"" + job + "\"}").extract().path("Status")));
        mm("GetAddressListImportJob", "{\"JobId\":\"" + job + "\"}").body("ImportedItemsCount", equalTo(2));
        mm("ListMembersOfAddressList", "{\"AddressListId\":\"" + list + "\"}").statusCode(200)
                .body("Addresses", hasSize(3));
        mm("ListMembersOfAddressList", "{\"AddressListId\":\"" + list + "\",\"Filter\":{\"AddressPrefix\":"
                + "\"gr\"}}").statusCode(200).body("Addresses", hasSize(1));
        mm("ListAddressListImportJobs", "{\"AddressListId\":\"" + list + "\"}").statusCode(200)
                .body("ImportJobs.JobId", hasItem(job));
        mm("DeregisterMemberFromAddressList", "{\"AddressListId\":\"" + list + "\",\"Address\":"
                + "\"ada@example.com\"}").statusCode(200);
        mm("GetMemberOfAddressList", "{\"AddressListId\":\"" + list + "\",\"Address\":\"ada@example.com\"}")
                .statusCode(400).body("__type", containsString("ResourceNotFoundException"));
        String arn = mm("GetAddressList", "{\"AddressListId\":\"" + list + "\"}").extract().path("AddressListArn");
        mm("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}").statusCode(200)
                .body("Tags[0].Key", equalTo("team"));
        mm("DeleteAddressList", "{\"AddressListId\":\"" + list + "\"}").statusCode(200);
    }

    @Test
    void ingressPointsReceiveMailThatRulesArchiveAndStore() throws IOException {
        given().header("Authorization", auth("s3")).put("/mm-inbound").then().statusCode(anyOf(equalTo(200),
                equalTo(409)));
        String archive = mm("CreateArchive", "{\"ArchiveName\":\"inbound\",\"Retention\":{\"RetentionPeriod\":"
                + "\"THREE_MONTHS\"}}").statusCode(200).extract().path("ArchiveId");
        String rs = ruleSet("archive-all", "[{\"Name\":\"keep\",\"Actions\":[{\"AddHeader\":{\"HeaderName\":"
                + "\"X-Floci\",\"HeaderValue\":\"seen\"}},{\"Archive\":{\"TargetArchive\":\"" + archive + "\"}},"
                + "{\"WriteToS3\":{\"S3Bucket\":\"mm-inbound\",\"S3Prefix\":\"mail\",\"RoleArn\":"
                + "\"arn:aws:iam::000000000000:role/mm\"}}]}]");
        String tp = trafficPolicy("only-example", "{\"Action\":\"DENY\",\"Conditions\":[{\"StringExpression\":"
                + "{\"Evaluate\":{\"Attribute\":\"RECIPIENT\"},\"Operator\":\"ENDS_WITH\",\"Values\":"
                + "[\"@blocked.example\"]}}]}");
        String ip = ingress("smtp-in", rs, tp, "OPEN", "");
        int port = port(ip);
        assertTrue(port > 0);

        List<String> replies = deliver(port, "sender@example.org", List.of("team@example.com"),
                message("Sender <sender@example.org>", "team@example.com", "hello", "first line"));
        assertTrue(replies.get(0).startsWith("220"), replies.toString());
        assertTrue(replies.get(5).startsWith("250"), replies.toString());

        try (Smtp smtp = new Smtp(port)) {
            smtp.read();
            smtp.send("EHLO client.example.com");
            smtp.send("MAIL FROM:<sender@example.org>");
            assertTrue(smtp.send("RCPT TO:<x@blocked.example>").startsWith("550"));
            smtp.send("QUIT");
        }

        given().header("Authorization", auth("s3")).get("/mm-inbound?list-type=2&prefix=mail/").then()
                .statusCode(200).body(containsString("<Key>mail/"));

        String search = mm("StartArchiveSearch", "{\"ArchiveId\":\"" + archive + "\",\"FromTimestamp\":0,"
                + "\"ToTimestamp\":4102444800,\"MaxResults\":10,\"Filters\":{\"Include\":[{\"StringExpression\":"
                + "{\"Evaluate\":{\"Attribute\":\"SUBJECT\"},\"Operator\":\"CONTAINS\",\"Values\":[\"hell\"]}}]}}")
                .statusCode(200).extract().path("SearchId");
        await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETED".equals(mm("GetArchiveSearch",
                "{\"SearchId\":\"" + search + "\"}").extract().path("Status.State")));
        String messageId = mm("GetArchiveSearchResults", "{\"SearchId\":\"" + search + "\"}").statusCode(200)
                .body("Rows", hasSize(1)).body("Rows[0].Subject", equalTo("hello"))
                .body("Rows[0].Envelope.From", equalTo("sender@example.org"))
                .extract().path("Rows[0].ArchivedMessageId");
        mm("ListArchiveSearches", "{\"ArchiveId\":\"" + archive + "\"}").statusCode(200)
                .body("Searches.SearchId", hasItem(search));

        String link = mm("GetArchiveMessage", "{\"ArchivedMessageId\":\"" + messageId + "\"}").statusCode(200)
                .body("Metadata.IngressPointId", equalTo(ip)).body("Envelope.To", hasItem("team@example.com"))
                .extract().path("MessageDownloadLink");
        String raw = given().get(URI.create(link).getPath()).then().statusCode(200).extract().asString();
        assertTrue(raw.startsWith("X-Floci: seen\r\n"), raw);
        assertTrue(raw.contains("\r\n.hidden\r\n"), "dot-unstuffed: " + raw);
        mm("GetArchiveMessageContent", "{\"ArchivedMessageId\":\"" + messageId + "\"}").statusCode(200)
                .body("Body.Text", containsString("first line"));

        String export = mm("StartArchiveExport", "{\"ArchiveId\":\"" + archive + "\",\"FromTimestamp\":0,"
                + "\"ToTimestamp\":4102444800,\"IncludeMetadata\":true,\"ExportDestinationConfiguration\":{\"S3\":"
                + "{\"S3Location\":\"s3://mm-inbound/exports\"}}}").statusCode(200).extract().path("ExportId");
        await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETED".equals(mm("GetArchiveExport",
                "{\"ExportId\":\"" + export + "\"}").extract().path("Status.State")));
        given().header("Authorization", auth("s3")).get("/mm-inbound?list-type=2&prefix=exports/" + export + "/")
                .then().statusCode(200).body(containsString(".eml")).body(containsString(".metadata.json"));
        mm("ListArchiveExports", "{\"ArchiveId\":\"" + archive + "\"}").statusCode(200)
                .body("Exports.ExportId", hasItem(export));

        mm("DeleteIngressPoint", "{\"IngressPointId\":\"" + ip + "\"}").statusCode(200);
        mm("DeleteArchive", "{\"ArchiveId\":\"" + archive + "\"}").statusCode(200);
        mm("GetArchive", "{\"ArchiveId\":\"" + archive + "\"}").statusCode(200)
                .body("ArchiveState", equalTo("PENDING_DELETION"));
        mm("DeleteRuleSet", "{\"RuleSetId\":\"" + rs + "\"}").statusCode(200);
        mm("DeleteTrafficPolicy", "{\"TrafficPolicyId\":\"" + tp + "\"}").statusCode(200);
    }

    @Test
    void authIngressPointsCheckThePassword() throws IOException {
        String rs = ruleSet("auth-drop", "[{\"Name\":\"drop\",\"Actions\":[{\"Drop\":{}}]}]");
        String tp = trafficPolicy("auth-allow", "");
        String ip = ingress("smtp-auth", rs, tp, "AUTH", ",\"IngressPointConfiguration\":{\"SmtpPassword\":"
                + "\"correct-horse-battery\"}");
        mm("GetIngressPoint", "{\"IngressPointId\":\"" + ip + "\"}").statusCode(200)
                .body("IngressPointAuthConfiguration.IngressPointPasswordConfiguration.SmtpPasswordVersion",
                        notNullValue());
        String user = Base64.getEncoder().encodeToString(("\0" + ip + "\0wrong")
                .getBytes(StandardCharsets.UTF_8));
        String good = Base64.getEncoder().encodeToString(("\0" + ip + "\0correct-horse-battery")
                .getBytes(StandardCharsets.UTF_8));
        try (Smtp smtp = new Smtp(port(ip))) {
            smtp.read();
            smtp.send("EHLO client.example.com");
            assertTrue(smtp.send("MAIL FROM:<a@example.org>").startsWith("530"));
            assertTrue(smtp.send("AUTH PLAIN " + user).startsWith("535"));
            assertEquals("235", smtp.send("AUTH PLAIN " + good).substring(0, 3));
            assertTrue(smtp.send("MAIL FROM:<a@example.org>").startsWith("250"));
            smtp.send("QUIT");
        }
        mm("DeleteIngressPoint", "{\"IngressPointId\":\"" + ip + "\"}").statusCode(200);

        String secret = given().header("Authorization", auth("secretsmanager"))
                .header("X-Amz-Target", "secretsmanager.CreateSecret").contentType("application/x-amz-json-1.1")
                .body("{\"Name\":\"mm-smtp\",\"SecretString\":\"{\\\"password\\\":\\\"from-secret\\\"}\"}")
                .post("/").then().statusCode(200).extract().path("ARN");
        String secretIp = ingress("smtp-secret", rs, tp, "AUTH", ",\"IngressPointConfiguration\":{\"SecretArn\":\""
                + secret + "\"}");
        String fromSecret = Base64.getEncoder().encodeToString("\0user\0from-secret".getBytes(StandardCharsets.UTF_8));
        try (Smtp smtp = new Smtp(port(secretIp))) {
            smtp.read();
            smtp.send("EHLO client.example.com");
            assertTrue(smtp.send("AUTH PLAIN " + user).startsWith("535"));
            assertEquals("235", smtp.send("AUTH PLAIN " + fromSecret).substring(0, 3));
            smtp.send("QUIT");
        }
        mm("DeleteIngressPoint", "{\"IngressPointId\":\"" + secretIp + "\"}").statusCode(200);
    }

    @Test
    void addonsAndRelaysAreManaged() {
        String sub = mm("CreateAddonSubscription", "{\"AddonName\":\"SPAMHAUS_DBL\"}").statusCode(200)
                .extract().path("AddonSubscriptionId");
        String inst = mm("CreateAddonInstance", "{\"AddonSubscriptionId\":\"" + sub + "\"}").statusCode(200)
                .extract().path("AddonInstanceId");
        mm("GetAddonInstance", "{\"AddonInstanceId\":\"" + inst + "\"}").statusCode(200)
                .body("AddonName", equalTo("SPAMHAUS_DBL"));
        mm("DeleteAddonSubscription", "{\"AddonSubscriptionId\":\"" + sub + "\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));
        mm("DeleteAddonInstance", "{\"AddonInstanceId\":\"" + inst + "\"}").statusCode(200);
        mm("DeleteAddonSubscription", "{\"AddonSubscriptionId\":\"" + sub + "\"}").statusCode(200);
        mm("ListAddonSubscriptions", "{}").statusCode(200);

        String relay = mm("CreateRelay", "{\"RelayName\":\"upstream\",\"ServerName\":\"smtp.example.com\","
                + "\"ServerPort\":587,\"Authentication\":{\"NoAuthentication\":{}}}").statusCode(200)
                .extract().path("RelayId");
        mm("GetRelay", "{\"RelayId\":\"" + relay + "\"}").statusCode(200).body("ServerPort", equalTo(587));
        mm("UpdateRelay", "{\"RelayId\":\"" + relay + "\",\"ServerPort\":2525}").statusCode(200);
        mm("ListRelays", "{}").statusCode(200).body("Relays.size()", greaterThan(0));
        mm("DeleteRelay", "{\"RelayId\":\"" + relay + "\"}").statusCode(200);
    }
}
