package io.github.hectorvent.floci.services.simpledb;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SimpleDbIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/sdb/aws4_request";
    private static final String DOMAIN = "BindingsDomain";

    private static RequestSpecification sdb() {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH)
                .formParam("Version", "2009-04-15");
    }

    @Test
    @Order(1)
    void createDomain_isIdempotent() {
        sdb()
                .formParam("Action", "CreateDomain")
                .formParam("DomainName", DOMAIN)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .contentType("application/xml")
                .body(containsString("CreateDomainResponse"));

        sdb()
                .formParam("Action", "CreateDomain")
                .formParam("DomainName", DOMAIN)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(2)
    void domainMetadata_returnsCounts() {
        sdb()
                .formParam("Action", "DomainMetadata")
                .formParam("DomainName", DOMAIN)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DomainMetadataResponse.DomainMetadataResult.ItemCount", equalTo("0"));
    }

    @Test
    @Order(3)
    void putThenGetAttributes() {
        sdb()
                .formParam("Action", "PutAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("ItemName", "get-test#1")
                .formParam("Attribute.1.Name", "color")
                .formParam("Attribute.1.Value", "green")
                .formParam("Attribute.1.Replace", "true")
                .formParam("Attribute.2.Name", "size")
                .formParam("Attribute.2.Value", "large")
                .formParam("Attribute.2.Replace", "true")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        sdb()
                .formParam("Action", "GetAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("ItemName", "get-test#1")
                .formParam("ConsistentRead", "true")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<Name>color</Name>"))
                .body(containsString("<Value>green</Value>"))
                .body(containsString("<Name>size</Name>"))
                .body(containsString("<Value>large</Value>"));
    }

    @Test
    @Order(4)
    void getAttributes_missingItem_returnsEmpty() {
        sdb()
                .formParam("Action", "GetAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("ItemName", "missing-item#404")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("<Attribute>")));
    }

    @Test
    @Order(5)
    void select_whereClause() {
        sdb()
                .formParam("Action", "PutAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("ItemName", "select-test#1")
                .formParam("Attribute.1.Name", "kind")
                .formParam("Attribute.1.Value", "select-target")
                .formParam("Attribute.1.Replace", "true")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        sdb()
                .formParam("Action", "Select")
                .formParam("SelectExpression", "select * from `" + DOMAIN + "` where kind = 'select-target'")
                .formParam("ConsistentRead", "true")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<Name>select-test#1</Name>"))
                .body(containsString("<Name>kind</Name>"))
                .body(containsString("<Value>select-target</Value>"));
    }

    @Test
    @Order(6)
    void batchPutThenGet() {
        sdb()
                .formParam("Action", "BatchPutAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("Item.1.ItemName", "batch-test#1")
                .formParam("Item.1.Attribute.1.Name", "batch")
                .formParam("Item.1.Attribute.1.Value", "one")
                .formParam("Item.1.Attribute.1.Replace", "true")
                .formParam("Item.2.ItemName", "batch-test#2")
                .formParam("Item.2.Attribute.1.Name", "batch")
                .formParam("Item.2.Attribute.1.Value", "two")
                .formParam("Item.2.Attribute.1.Replace", "true")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        sdb()
                .formParam("Action", "GetAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("ItemName", "batch-test#1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<Value>one</Value>"));
    }

    @Test
    @Order(7)
    void deleteAttributes_singleAndWholeItem() {
        sdb()
                .formParam("Action", "PutAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("ItemName", "delete-test#2")
                .formParam("Attribute.1.Name", "keep")
                .formParam("Attribute.1.Value", "me")
                .formParam("Attribute.1.Replace", "true")
                .formParam("Attribute.2.Name", "drop")
                .formParam("Attribute.2.Value", "me")
                .formParam("Attribute.2.Replace", "true")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        sdb()
                .formParam("Action", "DeleteAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("ItemName", "delete-test#2")
                .formParam("Attribute.1.Name", "drop")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        sdb()
                .formParam("Action", "GetAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("ItemName", "delete-test#2")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<Name>keep</Name>"))
                .body(not(containsString("<Name>drop</Name>")));

        sdb()
                .formParam("Action", "PutAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("ItemName", "delete-test#1")
                .formParam("Attribute.1.Name", "doomed")
                .formParam("Attribute.1.Value", "yes")
                .formParam("Attribute.1.Replace", "true")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        sdb()
                .formParam("Action", "DeleteAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("ItemName", "delete-test#1")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        sdb()
                .formParam("Action", "GetAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("ItemName", "delete-test#1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("<Attribute>")));
    }

    @Test
    @Order(8)
    void batchDeleteAttributes() {
        sdb()
                .formParam("Action", "BatchPutAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("Item.1.ItemName", "batch-delete#1")
                .formParam("Item.1.Attribute.1.Name", "x")
                .formParam("Item.1.Attribute.1.Value", "1")
                .formParam("Item.1.Attribute.1.Replace", "true")
                .formParam("Item.2.ItemName", "batch-delete#2")
                .formParam("Item.2.Attribute.1.Name", "x")
                .formParam("Item.2.Attribute.1.Value", "2")
                .formParam("Item.2.Attribute.1.Replace", "true")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        sdb()
                .formParam("Action", "BatchDeleteAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("Item.1.ItemName", "batch-delete#1")
                .formParam("Item.2.ItemName", "batch-delete#2")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        sdb()
                .formParam("Action", "GetAttributes")
                .formParam("DomainName", DOMAIN)
                .formParam("ItemName", "batch-delete#1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("<Attribute>")));
    }

    @Test
    @Order(9)
    void listDomains_includesFixture() {
        sdb()
                .formParam("Action", "ListDomains")
                .formParam("MaxNumberOfDomains", "100")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<DomainName>" + DOMAIN + "</DomainName>"));
    }

    @Test
    @Order(10)
    void domainMetadata_missingDomain_isNoSuchDomain() {
        sdb()
                .formParam("Action", "DomainMetadata")
                .formParam("DomainName", "does-not-exist")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("NoSuchDomain"));
    }

    @Test
    @Order(11)
    void createDomain_withoutAuthorizationHeader_routesByAction() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateDomain")
                .formParam("Version", "2009-04-15")
                .formParam("DomainName", "sigv2-domain")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("CreateDomainResponse"));
    }
}
