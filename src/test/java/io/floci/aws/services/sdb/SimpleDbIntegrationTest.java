package io.floci.aws.services.sdb;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SimpleDB: domains, multi-valued attributes, conditional puts and deletes, batches, metadata counted from the
 * items, and the select language (comparisons, like, between, in, is null, every(), itemName(), count(*), order by
 * and limit with paging), over the Query protocol with SimpleDB's own error form.
 */
@QuarkusTest
class SimpleDbIntegrationTest {

    static RequestSpecification sdb(String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/sdb/aws4_request")
                .contentType("application/x-www-form-urlencoded").formParam("Action", action)
                .formParam("Version", "2009-04-15");
    }

    static void put(String item, String... pairs) {
        RequestSpecification spec = sdb("PutAttributes").formParam("DomainName", "shop").formParam("ItemName", item);
        for (int i = 0; i < pairs.length; i += 2) {
            spec = spec.formParam("Attribute." + (i / 2 + 1) + ".Name", pairs[i])
                    .formParam("Attribute." + (i / 2 + 1) + ".Value", pairs[i + 1]);
        }
        spec.post("/").then().statusCode(200);
    }

    static XmlPath select(String expression) {
        return sdb("Select").formParam("SelectExpression", expression).post("/").then().statusCode(200).extract()
                .xmlPath();
    }

    static List<String> names(String expression) {
        return select(expression).getList("SelectResponse.SelectResult.Item.Name");
    }

    @Test
    void itemsAndSelect() {
        sdb("CreateDomain").formParam("DomainName", "x").post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        sdb("CreateDomain").formParam("DomainName", "shop").post("/").then().statusCode(200)
                .body(containsString("<BoxUsage>"));
        sdb("CreateDomain").formParam("DomainName", "shop").post("/").then().statusCode(200);
        sdb("GetAttributes").formParam("DomainName", "nope").formParam("ItemName", "a").post("/").then()
                .statusCode(400).body(containsString("<Errors><Error><Code>NoSuchDomain</Code>"));

        put("apple", "kind", "fruit", "color", "red", "color", "green", "price", "003");
        put("banana", "kind", "fruit", "color", "yellow", "price", "001");
        put("carrot", "kind", "vegetable", "color", "orange", "price", "002");
        put("durian", "kind", "fruit", "price", "009");

        // Multi-valued attributes, replace, and conditional puts.
        XmlPath apple = sdb("GetAttributes").formParam("DomainName", "shop").formParam("ItemName", "apple").post("/")
                .then().statusCode(200).extract().xmlPath();
        assertEquals(List.of("red", "green"),
                apple.getList("GetAttributesResponse.GetAttributesResult.Attribute.findAll { it.Name == 'color' }.Value"));
        sdb("PutAttributes").formParam("DomainName", "shop").formParam("ItemName", "apple")
                .formParam("Attribute.1.Name", "price").formParam("Attribute.1.Value", "004")
                .formParam("Attribute.1.Replace", "true").formParam("Expected.1.Name", "price")
                .formParam("Expected.1.Value", "999").post("/").then().statusCode(409)
                .body(containsString("ConditionalCheckFailed"));
        sdb("PutAttributes").formParam("DomainName", "shop").formParam("ItemName", "apple")
                .formParam("Attribute.1.Name", "price").formParam("Attribute.1.Value", "004")
                .formParam("Attribute.1.Replace", "true").formParam("Expected.1.Name", "price")
                .formParam("Expected.1.Value", "003").post("/").then().statusCode(200);
        sdb("PutAttributes").formParam("DomainName", "shop").formParam("ItemName", "apple")
                .formParam("Attribute.1.Name", "x").formParam("Attribute.1.Value", "1")
                .formParam("Expected.1.Name", "color").formParam("Expected.1.Value", "red").post("/").then()
                .statusCode(409).body(containsString("MultiValuedAttribute"));
        sdb("PutAttributes").formParam("DomainName", "shop").formParam("ItemName", "elderberry")
                .formParam("Attribute.1.Name", "kind").formParam("Attribute.1.Value", "fruit")
                .formParam("Expected.1.Name", "kind").formParam("Expected.1.Exists", "false").post("/").then()
                .statusCode(200);

        // Select.
        assertEquals(List.of("apple", "banana", "durian", "elderberry"),
                names("select * from shop where kind = 'fruit'"));
        assertEquals(List.of("apple"), names("select * from `shop` where color = 'green'"));
        assertEquals(List.of("apple"), names("select * from shop where every(color) in ('red', 'green') "
                + "and color = 'red'"));
        assertEquals(List.of("banana", "carrot"), names("select * from shop where price between '001' and '002'"));
        assertEquals(List.of("durian", "elderberry"), names("select * from shop where color is null"));
        assertEquals(List.of("banana"), names("select * from shop where color like 'yel%'"));
        assertEquals(List.of("carrot"), names("select * from shop where not kind = 'fruit'"));
        assertEquals(List.of("durian", "apple", "carrot", "banana"),
                names("select * from shop where price > '000' order by price desc"));
        assertEquals(List.of("apple", "banana"), names("select itemName() from shop where itemName() < 'c'"));
        select("select price from shop where kind = 'vegetable'").getList(
                "SelectResponse.SelectResult.Item.Attribute.Name").forEach(n -> assertEquals("price", n));
        assertEquals("4", select("select count(*) from shop where kind = 'fruit'")
                .getString("SelectResponse.SelectResult.Item.Attribute.Value"));
        sdb("Select").formParam("SelectExpression", "select * from shop order by price").post("/").then()
                .statusCode(400).body(containsString("InvalidSortExpression"));
        sdb("Select").formParam("SelectExpression", "select * shop").post("/").then().statusCode(400)
                .body(containsString("InvalidQueryExpression"));
        XmlPath first = select("select * from shop where kind = 'fruit' limit 2");
        String token = first.getString("SelectResponse.SelectResult.NextToken");
        assertEquals(List.of("durian", "elderberry"), sdb("Select")
                .formParam("SelectExpression", "select * from shop where kind = 'fruit' limit 2")
                .formParam("NextToken", token).post("/").then().extract().xmlPath()
                .getList("SelectResponse.SelectResult.Item.Name"));

        // Batches and deletes.
        sdb("BatchPutAttributes").formParam("DomainName", "shop").formParam("Item.1.ItemName", "fig")
                .formParam("Item.1.Attribute.1.Name", "kind").formParam("Item.1.Attribute.1.Value", "fruit")
                .formParam("Item.2.ItemName", "fig").formParam("Item.2.Attribute.1.Name", "kind")
                .formParam("Item.2.Attribute.1.Value", "x").post("/").then().statusCode(400)
                .body(containsString("DuplicateItemName"));
        sdb("DeleteAttributes").formParam("DomainName", "shop").formParam("ItemName", "apple")
                .formParam("Attribute.1.Name", "color").formParam("Attribute.1.Value", "red").post("/").then()
                .statusCode(200);
        assertEquals(List.of(), names("select * from shop where color = 'red'"));
        sdb("BatchDeleteAttributes").formParam("DomainName", "shop").formParam("Item.1.ItemName", "durian")
                .formParam("Item.2.ItemName", "elderberry").post("/").then().statusCode(200);
        XmlPath metadata = sdb("DomainMetadata").formParam("DomainName", "shop").post("/").then().statusCode(200)
                .extract().xmlPath();
        assertEquals("3", metadata.getString("DomainMetadataResponse.DomainMetadataResult.ItemCount"));
        assertEquals("3", metadata.getString("DomainMetadataResponse.DomainMetadataResult.AttributeNameCount"));

        // Listing pages, and a SigV2 request (no Authorization header) still reaches SimpleDB.
        sdb("CreateDomain").formParam("DomainName", "shop2").post("/").then().statusCode(200);
        String listToken = sdb("ListDomains").formParam("MaxNumberOfDomains", "1").post("/").then()
                .body("ListDomainsResponse.ListDomainsResult.DomainName", equalTo("shop")).extract().xmlPath()
                .getString("ListDomainsResponse.ListDomainsResult.NextToken");
        given().contentType("application/x-www-form-urlencoded").formParam("Action", "ListDomains")
                .formParam("Version", "2009-04-15").formParam("SignatureVersion", "2")
                .formParam("NextToken", listToken).post("/").then().statusCode(200)
                .body("ListDomainsResponse.ListDomainsResult.DomainName", equalTo("shop2"));
        sdb("DeleteDomain").formParam("DomainName", "shop2").post("/").then().statusCode(200);
        sdb("DeleteDomain").formParam("DomainName", "shop2").post("/").then().statusCode(200);
    }
}
