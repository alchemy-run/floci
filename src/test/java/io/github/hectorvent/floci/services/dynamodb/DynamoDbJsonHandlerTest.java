package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamoDbJsonHandlerTest {

    private DynamoDbService service;
    private ObjectMapper mapper;
    private DynamoDbJsonHandler handler;

    @BeforeEach
    void setUp() {
        InMemoryStorage<String, TableDefinition> storage = new InMemoryStorage<>();
        service = new DynamoDbService(storage);
        mapper = new ObjectMapper();
        DynamoDbStreamService streamService = new DynamoDbStreamService(mapper, storage);
        handler = new DynamoDbJsonHandler(service, streamService, null, mapper);
    }

    private TableDefinition createUsersTable(String region) {
        return service.createTable("Users",
                List.of(new KeySchemaElement("userId", "HASH")),
                List.of(new AttributeDefinition("userId", "S")),
                5L, 5L, region);
    }

    private ObjectNode attributeValue(String type, String value) {
        ObjectNode attrValue = mapper.createObjectNode();
        attrValue.put(type, value);
        return attrValue;
    }

    private ObjectNode item(String... kvPairs) {
        ObjectNode node = mapper.createObjectNode();
        for (int i = 0; i < kvPairs.length; i += 2) {
            node.set(kvPairs[i], attributeValue("S", kvPairs[i + 1]));
        }
        return node;
    }

    private JsonNode createRequest(String tableName, JsonNode key, String updateExpression, 
    JsonNode exprAttrNames, JsonNode exprAttrValues, String returnValues){
        ObjectNode node = mapper.createObjectNode();
        node.put("TableName", tableName);
        node.set("Key", key);
        node.put("UpdateExpression", updateExpression);
        if (exprAttrNames != null){
            node.set("ExpressionAttributeNames", exprAttrNames);
        }
        if (exprAttrValues != null){
            node.set("ExpressionAttributeValues", exprAttrValues);
        }
        node.put("ReturnValues", returnValues);
        return node;
    }

    @Test
    void updateItemReturnValuesUpdatedNew()  throws Exception {
        createUsersTable("us-east-1");

        service.putItem("Users", item("userId", "u-fallback", "delAttr", "old", "changeAttr", "val1", "sameAttr", "static"), "us-east-1");

        ObjectNode key = item("userId", "u-fallback");

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.put(":changeVal", attributeValue("S", "val2"));
        exprValues.put(":newVal", attributeValue("S", "newVal"));

        JsonNode request = createRequest("Users", key, 
        "SET changeAttr = :changeVal,  newAttr = :newVal, REMOVE delAttr",
        null, exprValues, "UPDATED_NEW");

        Response response = null;
        
        response = handler.handle("UpdateItem", request, "us-east-1");
        assertNotNull(response);

        JsonNode responseData = mapper.convertValue(response.getEntity(), JsonNode.class);

        assertNotNull(responseData);
        assertTrue(responseData.has("Attributes"), "Attributes property must be present");
        JsonNode attr = responseData.get("Attributes");

        assertTrue(attr.has("changeAttr"), "Attributes should have changeAttr");
        assertTrue(attr.get("changeAttr").has("S"), "changeAttr should have S");
        assertEquals("val2", attr.get("changeAttr").get("S").asText());

        assertTrue(attr.has("newAttr"), "Attributes should have newAttr");
        assertTrue(attr.get("newAttr").has("S"), "newAttr should have S");
        assertEquals("newVal", attr.get("newAttr").get("S").asText());

        assertFalse(attr.has("delAttr"), "Attributes should not have delAttr");

        assertFalse(attr.has("sameAttr"), "Attributes should not have sameAttr");
    }
    
    @Test
    void updateItemReturnValuesUpdatedNewOnNewItem() throws Exception {
        createUsersTable("us-east-1");

        // Item does not exist - UpdateItem creates it
        ObjectNode key = item("userId", "u-new");

        ObjectNode exprValues = mapper.createObjectNode();
        ObjectNode startVal = mapper.createObjectNode();
        startVal.put("N", "60000000");
        ObjectNode incVal = mapper.createObjectNode();
        incVal.put("N", "1");
        exprValues.set(":start", startVal);
        exprValues.set(":inc", incVal);

        ObjectNode exprNames = mapper.createObjectNode();
        exprNames.put("#cnt", "counter");

        JsonNode request = createRequest("Users", key,
                "SET #cnt = if_not_exists(#cnt, :start) + :inc",
                exprNames, exprValues, "UPDATED_NEW");

        Response response = handler.handle("UpdateItem", request, "us-east-1");
        assertNotNull(response);

        JsonNode responseData = mapper.convertValue(response.getEntity(), JsonNode.class);

        assertNotNull(responseData);
        assertTrue(responseData.has("Attributes"), "Attributes must be present when item is newly created");
        JsonNode attr = responseData.get("Attributes");

        assertTrue(attr.has("counter"), "Attributes should have counter");
        assertEquals("60000001", attr.get("counter").get("N").asText());

        assertFalse(attr.has("userId"), "UPDATED_NEW should not include key attributes");
    }

    @Test
    void updateItemReturnValuesUpdatedOld()  throws Exception {
        createUsersTable("us-east-1");

        service.putItem("Users", item("userId", "u-fallback", "delAttr", "old", "changeAttr", "val1", "sameAttr", "static"), "us-east-1");

        ObjectNode key = item("userId", "u-fallback");

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.put(":changeVal", attributeValue("S", "val2"));
        exprValues.put(":newVal", attributeValue("S", "newVal"));

        JsonNode request = createRequest("Users", key, 
        "SET changeAttr = :changeVal,  newAttr = :newVal, REMOVE delAttr",
        null, exprValues, "UPDATED_OLD");

        Response response = null;
        
        response = handler.handle("UpdateItem", request, "us-east-1");
        assertNotNull(response);

        JsonNode responseData = mapper.convertValue(response.getEntity(), JsonNode.class);

        assertNotNull(responseData);
        assertTrue(responseData.has("Attributes"), "Attributes property must be present");
        JsonNode attr = responseData.get("Attributes");

        assertTrue(attr.has("changeAttr"), "Attributes should have changeAttr");
        assertTrue(attr.get("changeAttr").has("S"), "changeAttr should have S");
        assertEquals("val1", attr.get("changeAttr").get("S").asText());

        assertFalse(attr.has("newAttr"), "Attributes should not have newAttr");

        assertTrue(attr.has("delAttr"), "Attributes should have delAttr");
        assertTrue(attr.get("delAttr").has("S"), "delAttr should have S");
        assertEquals("old", attr.get("delAttr").get("S").asText());

        assertFalse(attr.has("sameAttr"), "Attributes should not have sameAttr");
    }
    
    @Test
    void updateItemReturnValuesAllOld()  throws Exception {
        createUsersTable("us-east-1");

        service.putItem("Users", item("userId", "u-fallback", "delAttr", "old", "changeAttr", "val1", "sameAttr", "static"), "us-east-1");

        ObjectNode key = item("userId", "u-fallback");

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.put(":changeVal", attributeValue("S", "val2"));
        exprValues.put(":newVal", attributeValue("S", "newVal"));

        JsonNode request = createRequest("Users", key, 
        "SET changeAttr = :changeVal,  newAttr = :newVal, REMOVE delAttr",
        null, exprValues, "ALL_OLD");

        Response response = null;
        
        response = handler.handle("UpdateItem", request, "us-east-1");
        assertNotNull(response);

        JsonNode responseData = mapper.convertValue(response.getEntity(), JsonNode.class);

        assertNotNull(responseData);
        assertTrue(responseData.has("Attributes"), "Attributes property must be present");
        JsonNode attr = responseData.get("Attributes");

        assertTrue(attr.has("changeAttr"), "Attributes should have changeAttr");
        assertTrue(attr.get("changeAttr").has("S"), "changeAttr should have S");
        assertEquals("val1", attr.get("changeAttr").get("S").asText());

        assertFalse(attr.has("newAttr"), "Attributes should not have newAttr");

        assertTrue(attr.has("delAttr"), "Attributes should have delAttr");
        assertTrue(attr.get("delAttr").has("S"), "delAttr should have S");
        assertEquals("old", attr.get("delAttr").get("S").asText());

        assertTrue(attr.has("sameAttr"), "Attributes should have sameAttr");
        assertTrue(attr.get("sameAttr").has("S"), "sameAttr should have S");
        assertEquals("static", attr.get("sameAttr").get("S").asText());
    }
    
    @Test
    void updateItemReturnValuesAllNew()  throws Exception {
        createUsersTable("us-east-1");

        service.putItem("Users", item("userId", "u-fallback", "delAttr", "old", "changeAttr", "val1", "sameAttr", "static"), "us-east-1");

        ObjectNode key = item("userId", "u-fallback");

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.put(":changeVal", attributeValue("S", "val2"));
        exprValues.put(":newVal", attributeValue("S", "newVal"));

        JsonNode request = createRequest("Users", key, 
        "SET changeAttr = :changeVal,  newAttr = :newVal, REMOVE delAttr",
        null, exprValues, "ALL_NEW");

        Response response = null;
        
        response = handler.handle("UpdateItem", request, "us-east-1");
        assertNotNull(response);

        JsonNode responseData = mapper.convertValue(response.getEntity(), JsonNode.class);

        assertNotNull(responseData);
        assertTrue(responseData.has("Attributes"), "Attributes property must be present");
        JsonNode attr = responseData.get("Attributes");

        assertTrue(attr.has("changeAttr"), "Attributes should have changeAttr");
        assertTrue(attr.get("changeAttr").has("S"), "changeAttr should have S");
        assertEquals("val2", attr.get("changeAttr").get("S").asText());

        assertTrue(attr.has("newAttr"), "Attributes should have newAttr");
        assertTrue(attr.get("newAttr").has("S"), "newAttr should have S");
        assertEquals("newVal", attr.get("newAttr").get("S").asText());

        assertFalse(attr.has("delAttr"), "Attributes should not have delAttr");

        assertTrue(attr.has("sameAttr"), "Attributes should have sameAttr");
        assertTrue(attr.get("sameAttr").has("S"), "sameAttr should have S");
        assertEquals("static", attr.get("sameAttr").get("S").asText());
    }
    
    @Test
    void updateItemReturnValuesNone()  throws Exception {
        createUsersTable("us-east-1");

        service.putItem("Users", item("userId", "u-fallback", "delAttr", "old", "changeAttr", "val1", "sameAttr", "static"), "us-east-1");

        ObjectNode key = item("userId", "u-fallback");

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.put(":changeVal", attributeValue("S", "val2"));
        exprValues.put(":newVal", attributeValue("S", "newVal"));

        JsonNode request = createRequest("Users", key, 
        "SET changeAttr = :changeVal,  newAttr = :newVal, REMOVE delAttr",
        null, exprValues, "NONE");

        Response response = null;
        
        response = handler.handle("UpdateItem", request, "us-east-1");
        assertNotNull(response);

        JsonNode responseData = mapper.convertValue(response.getEntity(), JsonNode.class);

        assertNotNull(responseData);
        assertFalse(responseData.has("Attributes"), "Attributes property must not be present");
    }

    // Reproduces #1604
    @Test
    void transactWriteItemsCancellationReasonMessageIsNullForNonFailedItems() throws Exception {
        createUsersTable("us-east-1");
        service.putItem("Users", item("userId", "A"), "us-east-1");

        ObjectNode condCheckA = mapper.createObjectNode();
        condCheckA.put("TableName", "Users");
        condCheckA.set("Key", item("userId", "A"));
        condCheckA.put("ConditionExpression", "attribute_exists(userId)");

        ObjectNode condCheckB = mapper.createObjectNode();
        condCheckB.put("TableName", "Users");
        condCheckB.set("Key", item("userId", "B"));
        condCheckB.put("ConditionExpression", "attribute_exists(userId)");

        ObjectNode txItemA = mapper.createObjectNode();
        txItemA.set("ConditionCheck", condCheckA);
        ObjectNode txItemB = mapper.createObjectNode();
        txItemB.set("ConditionCheck", condCheckB);

        ObjectNode request = mapper.createObjectNode();
        ArrayNode txItems = request.putArray("TransactItems");
        txItems.add(txItemA);
        txItems.add(txItemB);

        Response response = handler.handle("TransactWriteItems", request, "us-east-1");

        assertEquals(400, response.getStatus());

        JsonNode body = mapper.convertValue(response.getEntity(), JsonNode.class);
        assertEquals("TransactionCanceledException", body.get("__type").asText());

        ArrayNode reasons = (ArrayNode) body.get("CancellationReasons");
        assertEquals(2, reasons.size());

        assertEquals("None", reasons.get(0).get("Code").asText());
        assertNull(reasons.get(0).get("Message"), "non failed item must not have a Message field");
    }

    @Test
    void describeTableOmitsStreamSpecificationWhenDisabled() throws Exception {
        ObjectNode create = mapper.createObjectNode();
        create.put("TableName", "Users");
        create.putArray("KeySchema").addObject()
                .put("AttributeName", "userId").put("KeyType", "HASH");
        create.putArray("AttributeDefinitions").addObject()
                .put("AttributeName", "userId").put("AttributeType", "S");
        create.putObject("StreamSpecification")
                .put("StreamEnabled", true)
                .put("StreamViewType", "KEYS_ONLY");
        assertEquals(200, handler.handle("CreateTable", create, "us-east-1").getStatus());

        ObjectNode describe = mapper.createObjectNode();
        describe.put("TableName", "Users");
        JsonNode enabled = mapper.convertValue(
                handler.handle("DescribeTable", describe, "us-east-1").getEntity(), JsonNode.class);
        assertTrue(enabled.path("Table").has("StreamSpecification"));
        assertEquals(true, enabled.path("Table").path("StreamSpecification").path("StreamEnabled").asBoolean());
        assertEquals("KEYS_ONLY", enabled.path("Table").path("StreamSpecification").path("StreamViewType").asText());

        ObjectNode disable = mapper.createObjectNode();
        disable.put("TableName", "Users");
        disable.putObject("StreamSpecification").put("StreamEnabled", false);
        JsonNode afterDisable = mapper.convertValue(
                handler.handle("UpdateTable", disable, "us-east-1").getEntity(), JsonNode.class);
        assertFalse(afterDisable.path("TableDescription").has("StreamSpecification"),
                "AWS omits StreamSpecification when streams are disabled");

        JsonNode described = mapper.convertValue(
                handler.handle("DescribeTable", describe, "us-east-1").getEntity(), JsonNode.class);
        assertFalse(described.path("Table").has("StreamSpecification"),
                "DescribeTable must omit StreamSpecification after disable");
        assertFalse(described.path("Table").path("StreamSpecification").has("StreamViewType"));
    }

    @Test
    void resourcePolicyRoundTripAndMissingPolicy() throws Exception {
        TableDefinition table = createUsersTable("us-east-1");
        String arn = table.getTableArn();
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"AllowDescribe\",\"Effect\":\"Allow\"}]}";

        ObjectNode getMissing = mapper.createObjectNode();
        getMissing.put("ResourceArn", arn);
        AwsException missing = assertThrows(AwsException.class,
                () -> handler.handle("GetResourcePolicy", getMissing, "us-east-1"));
        assertEquals("PolicyNotFoundException", missing.getErrorCode());

        ObjectNode put = mapper.createObjectNode();
        put.put("ResourceArn", arn);
        put.put("Policy", policy);
        assertEquals(200, handler.handle("PutResourcePolicy", put, "us-east-1").getStatus());

        JsonNode got = mapper.convertValue(
                handler.handle("GetResourcePolicy", getMissing, "us-east-1").getEntity(), JsonNode.class);
        assertTrue(got.path("Policy").asText().contains("AllowDescribe"));

        ObjectNode putUpdated = mapper.createObjectNode();
        putUpdated.put("ResourceArn", arn);
        putUpdated.put("Policy", policy.replace("AllowDescribe", "AllowGet"));
        handler.handle("PutResourcePolicy", putUpdated, "us-east-1");
        JsonNode updated = mapper.convertValue(
                handler.handle("GetResourcePolicy", getMissing, "us-east-1").getEntity(), JsonNode.class);
        assertTrue(updated.path("Policy").asText().contains("AllowGet"));
        assertFalse(updated.path("Policy").asText().contains("AllowDescribe"));

        ObjectNode delete = mapper.createObjectNode();
        delete.put("ResourceArn", arn);
        assertEquals(200, handler.handle("DeleteResourcePolicy", delete, "us-east-1").getStatus());
        AwsException afterDelete = assertThrows(AwsException.class,
                () -> handler.handle("GetResourcePolicy", getMissing, "us-east-1"));
        assertEquals("PolicyNotFoundException", afterDelete.getErrorCode());
    }

    @Test
    void contributorInsightsEnableDisableAndDescribe() throws Exception {
        createUsersTable("us-east-1");

        ObjectNode describe = mapper.createObjectNode();
        describe.put("TableName", "Users");
        JsonNode initial = mapper.convertValue(
                handler.handle("DescribeContributorInsights", describe, "us-east-1").getEntity(), JsonNode.class);
        assertEquals("DISABLED", initial.path("ContributorInsightsStatus").asText());
        assertTrue(initial.path("ContributorInsightsRuleList").isArray());
        assertEquals(0, initial.path("ContributorInsightsRuleList").size());

        ObjectNode enable = mapper.createObjectNode();
        enable.put("TableName", "Users");
        enable.put("ContributorInsightsAction", "ENABLE");
        JsonNode enabled = mapper.convertValue(
                handler.handle("UpdateContributorInsights", enable, "us-east-1").getEntity(), JsonNode.class);
        assertEquals("ENABLED", enabled.path("ContributorInsightsStatus").asText());

        JsonNode described = mapper.convertValue(
                handler.handle("DescribeContributorInsights", describe, "us-east-1").getEntity(), JsonNode.class);
        assertEquals("ENABLED", described.path("ContributorInsightsStatus").asText());
        assertEquals("Users", described.path("TableName").asText());

        ObjectNode disable = mapper.createObjectNode();
        disable.put("TableName", "Users");
        disable.put("ContributorInsightsAction", "DISABLE");
        JsonNode disabled = mapper.convertValue(
                handler.handle("UpdateContributorInsights", disable, "us-east-1").getEntity(), JsonNode.class);
        assertEquals("DISABLED", disabled.path("ContributorInsightsStatus").asText());

        JsonNode listed = mapper.convertValue(
                handler.handle("ListContributorInsights", mapper.createObjectNode(), "us-east-1").getEntity(),
                JsonNode.class);
        assertEquals(1, listed.path("ContributorInsightsSummaries").size());
        assertEquals("DISABLED", listed.path("ContributorInsightsSummaries").get(0)
                .path("ContributorInsightsStatus").asText());
    }
}
