package io.floci.aws.services.datazone;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Amazon DataZone over REST: domains and their units, projects and memberships, the catalog (types, assets,
 * listings, search, glossaries, predictions, time series), metadata rules, data products, subscriptions granted
 * through Lake Formation, environments provisioned from the DefaultDataLake blueprint, Glue data source runs,
 * lineage, and notebooks.
 */
@QuarkusTest
class DataZoneIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String TRUST = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"datazone.amazonaws.com\"},\"Action\":[\"sts:AssumeRole\","
            + "\"sts:TagSession\"]}]}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/" + service + "/aws4_request";
    }

    private static RequestSpecification dz() {
        return given().header("Authorization", auth("datazone")).contentType("application/json");
    }

    private static ValidatableResponse post(String path, String body) {
        return dz().body(body).post(path).then();
    }

    private static ValidatableResponse get(String path) {
        return dz().get(path).then();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String role(String name, String trust) {
        given().header("Authorization", auth("iam")).formParam("Action", "CreateRole").formParam("RoleName", name)
                .formParam("AssumeRolePolicyDocument", trust).post("/").then().statusCode(200);
        return "arn:aws:iam::" + ACCOUNT + ":role/" + name;
    }

    private static String domain(String name) {
        String role = role("dz-exec-" + suffix(), TRUST);
        return post("/v2/domains", "{\"name\":\"" + name + "\",\"domainExecutionRole\":\"" + role + "\"}")
                .statusCode(201).body("status", equalTo("AVAILABLE")).extract().path("id");
    }

    private static String project(String domainId, String name) {
        return post("/v2/domains/" + domainId + "/projects", "{\"name\":\"" + name + "\"}").statusCode(201)
                .extract().path("id");
    }

    private static void glue(String target, String body) {
        given().header("Authorization", auth("glue")).header("X-Amz-Target", "AWSGlue." + target)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then().statusCode(200);
    }

    @Test
    void domainLifecycleTagsAndValidation() {
        String untrusted = role("dz-untrusted-" + suffix(), TRUST.replace("datazone.amazonaws.com",
                "lambda.amazonaws.com"));
        post("/v2/domains", "{\"name\":\"bad-" + suffix() + "\",\"domainExecutionRole\":\"" + untrusted + "\"}")
                .statusCode(400).body("message", containsString("Unable to assume role"));
        post("/v2/domains", "{\"name\":\"bad-" + suffix() + "\",\"domainExecutionRole\":\"not-a-role\"}")
                .statusCode(400).body("message", containsString("failed to satisfy constraint"));

        String name = "dz-" + suffix();
        String domainId = domain(name);
        ValidatableResponse domain = get("/v2/domains/" + domainId).statusCode(200).body("name", equalTo(name))
                .body("portalUrl", equalTo("https://" + domainId + ".datazone.us-east-1.on.aws"))
                .body("rootDomainUnitId", notNullValue());
        String arn = domain.extract().path("arn");
        String rootUnit = domain.extract().path("rootDomainUnitId");
        get("/v2/domains").statusCode(200).body("items.id", hasItem(domainId));
        dz().body("{\"description\":\"updated\"}").put("/v2/domains/" + domainId).then().statusCode(200)
                .body("description", equalTo("updated"));

        // An ARN in the tags path may arrive with its '/' unencoded.
        post("/tags/" + arn, "{\"tags\":{\"team\":\"data\"}}").statusCode(200);
        get("/tags/" + arn).statusCode(200).body("tags.team", equalTo("data"));

        String unit = post("/v2/domains/" + domainId + "/domain-units", "{\"name\":\"finance\","
                + "\"parentDomainUnitIdentifier\":\"" + rootUnit + "\"}").statusCode(201).extract().path("id");
        get("/v2/domains/" + domainId + "/domain-units/" + unit).statusCode(200).body("name", equalTo("finance"));
        post("/v2/domains/" + domainId + "/entities/DOMAIN_UNIT/" + unit + "/addOwner",
                "{\"owner\":{\"user\":{\"userIdentifier\":\"arn:aws:iam::" + ACCOUNT + ":role/steward\"}}}")
                .statusCode(201);
        get("/v2/domains/" + domainId + "/entities/DOMAIN_UNIT/" + unit + "/owners").statusCode(200)
                .body("owners", hasSize(2));

        dz().delete("/v2/domains/" + domainId).then().statusCode(202);
        get("/v2/domains/" + domainId).statusCode(403).body("message", containsString("not permitted"));
    }

    @Test
    void catalogPublishesSearchesAndEnforcesRules() {
        String domainId = domain("dz-catalog-" + suffix());
        String rootUnit = get("/v2/domains/" + domainId).extract().path("rootDomainUnitId");
        String projectId = project(domainId, "producers");
        String formType = "Ownership" + suffix();
        post("/v2/domains/" + domainId + "/form-types", "{\"name\":\"" + formType + "\",\"owningProjectIdentifier\":\""
                + projectId + "\",\"model\":{\"smithy\":\"structure " + formType + " { owner: String }\"}}")
                .statusCode(201).body("revision", equalTo("1"));
        String assetType = "Report" + suffix();
        post("/v2/domains/" + domainId + "/asset-types", "{\"name\":\"" + assetType + "\",\"owningProjectIdentifier\":\""
                + projectId + "\",\"formsInput\":{\"OwnershipForm\":{\"typeIdentifier\":\"" + formType
                + "\",\"typeRevision\":\"1\",\"required\":true}}}").statusCode(201);

        // A required form is enforced, and form content must be JSON.
        post("/v2/domains/" + domainId + "/assets", "{\"name\":\"sales\",\"typeIdentifier\":\"" + assetType
                + "\",\"owningProjectIdentifier\":\"" + projectId + "\"}").statusCode(400)
                .body("message", containsString("requires form OwnershipForm"));
        String assetId = post("/v2/domains/" + domainId + "/assets", "{\"name\":\"daily_sales\",\"description\":"
                + "\"Daily sales\",\"typeIdentifier\":\"" + assetType + "\",\"owningProjectIdentifier\":\"" + projectId
                + "\",\"formsInput\":[{\"formName\":\"OwnershipForm\",\"content\":\"{\\\"owner\\\":\\\"finance\\\"}\"}]}")
                .statusCode(201).body("revision", equalTo("1")).body("formsOutput[0].typeName", equalTo(formType))
                .extract().path("id");
        post("/v2/domains/" + domainId + "/assets/" + assetId + "/revisions", "{\"name\":\"daily_sales\","
                + "\"description\":\"Daily sales by region\"}").statusCode(200).body("revision", equalTo("2"))
                .body("formsOutput", hasSize(1));
        get("/v2/domains/" + domainId + "/assets/" + assetId + "?revision=1").statusCode(200)
                .body("description", equalTo("Daily sales"));

        // A rule on the root unit requires a glossary term before publishing.
        String glossary = post("/v2/domains/" + domainId + "/glossaries", "{\"name\":\"Finance\","
                + "\"owningProjectIdentifier\":\"" + projectId + "\"}").statusCode(201).extract().path("id");
        String term = post("/v2/domains/" + domainId + "/glossary-terms", "{\"glossaryIdentifier\":\"" + glossary
                + "\",\"name\":\"Revenue\"}").statusCode(201).extract().path("id");
        post("/v2/domains/" + domainId + "/rules", "{\"name\":\"needs-terms\",\"target\":{\"domainUnitTarget\":"
                + "{\"domainUnitId\":\"" + rootUnit + "\"}},\"action\":\"CREATE_LISTING_CHANGE_SET\",\"scope\":"
                + "{\"assetType\":{\"selectionMode\":\"ALL\"}},\"detail\":{\"glossaryTermEnforcementDetail\":"
                + "{\"requiredGlossaryTermIds\":[\"" + term + "\"]}}}").statusCode(201)
                .body("ruleType", equalTo("GLOSSARY_TERM_ENFORCEMENT"));
        String publish = "{\"entityIdentifier\":\"" + assetId + "\",\"entityType\":\"ASSET\",\"action\":\"PUBLISH\"}";
        post("/v2/domains/" + domainId + "/listings/change-set", publish).statusCode(400)
                .body("message", containsString("requires glossary term"));
        post("/v2/domains/" + domainId + "/assets/" + assetId + "/revisions", "{\"name\":\"daily_sales\","
                + "\"glossaryTerms\":[\"" + term + "\"]}").statusCode(200).body("revision", equalTo("3"));
        String listingId = post("/v2/domains/" + domainId + "/listings/change-set", publish).statusCode(200)
                .body("status", equalTo("ACTIVE")).extract().path("listingId");
        get("/v2/domains/" + domainId + "/listings/" + listingId).statusCode(200)
                .body("item.assetListing.assetRevision", equalTo("3"))
                .body("item.assetListing.glossaryTerms[0].name", equalTo("Revenue"));

        post("/v2/domains/" + domainId + "/listings/search", "{\"searchText\":\"sales\"}").statusCode(200)
                .body("totalMatchCount", equalTo(1)).body("items[0].assetListing.listingId", equalTo(listingId));
        post("/v2/domains/" + domainId + "/search", "{\"searchScope\":\"ASSET\",\"owningProjectIdentifier\":\""
                + projectId + "\",\"additionalAttributes\":[\"FORMS\"],\"filters\":{\"filter\":{\"attribute\":"
                + "\"OwnershipForm.owner\",\"value\":\"finance\"}}}").statusCode(200)
                .body("items[0].assetItem.identifier", equalTo(assetId));
        post("/v2/domains/" + domainId + "/types-search", "{\"searchScope\":\"ASSET_TYPE\",\"managed\":true}")
                .statusCode(200).body("items.assetTypeItem.name", hasItem("amazon.datazone.GlueTableAssetType"));

        // Time series data points on the asset.
        long now = Instant.now().getEpochSecond();
        post("/v2/domains/" + domainId + "/entities/ASSET/" + assetId + "/time-series-data-points",
                "{\"forms\":[{\"formName\":\"quality\",\"typeIdentifier\":\"amazon.datazone.DataQualityResultFormType\","
                        + "\"timestamp\":" + now + ",\"content\":\"{\\\"passingPercentage\\\":99}\"}]}")
                .statusCode(201).body("forms[0].id", notNullValue());
        get("/v2/domains/" + domainId + "/entities/ASSET/" + assetId + "/time-series-data-points?formName=quality")
                .statusCode(200).body("items", hasSize(1));
        get("/v2/domains/" + domainId + "/assets/" + assetId).statusCode(200)
                .body("latestTimeSeriesDataPointFormsOutput[0].formName", equalTo("quality"))
                .body("listing.listingStatus", equalTo("ACTIVE"));

        // Metadata generation predicts a business name the owners accept as a new revision.
        String run = post("/v2/domains/" + domainId + "/metadata-generation-runs", "{\"type\":\"BUSINESS_NAMES\","
                + "\"target\":{\"type\":\"ASSET\",\"identifier\":\"" + assetId + "\"},\"owningProjectIdentifier\":\""
                + projectId + "\"}").statusCode(200).extract().path("id");
        get("/v2/domains/" + domainId + "/metadata-generation-runs/" + run).statusCode(200)
                .body("status", equalTo("SUCCEEDED"));
        dz().body("{}").put("/v2/domains/" + domainId + "/assets/" + assetId + "/accept-predictions").then()
                .statusCode(200).body("revision", equalTo("4"));
        get("/v2/domains/" + domainId + "/assets/" + assetId).statusCode(200).body("name", equalTo("Daily Sales"));

        // A data product over the asset is published as its own listing.
        String product = post("/v2/domains/" + domainId + "/data-products", "{\"name\":\"sales-product\","
                + "\"owningProjectIdentifier\":\"" + projectId + "\",\"glossaryTerms\":[\"" + term + "\"],\"items\":"
                + "[{\"itemType\":\"ASSET\",\"identifier\":\"" + assetId + "\"}]}").statusCode(201)
                .body("status", equalTo("CREATED")).extract().path("id");
        post("/v2/domains/" + domainId + "/listings/change-set", "{\"entityIdentifier\":\"" + product
                + "\",\"entityType\":\"DATA_PRODUCT\",\"action\":\"PUBLISH\"}").statusCode(200);
        post("/v2/domains/" + domainId + "/listings/search", "{\"searchText\":\"sales-product\"}").statusCode(200)
                .body("items[0].dataProductListing.entityId", equalTo(product))
                .body("items[0].dataProductListing.items", hasSize(1));
        dz().delete("/v2/domains/" + domainId + "/data-products/" + product).then().statusCode(409);
    }

    @Test
    void subscriptionsAreRequestedAcceptedAndRevoked() {
        String domainId = domain("dz-subs-" + suffix());
        String producer = project(domainId, "producer");
        String consumer = project(domainId, "consumer");
        String assetId = post("/v2/domains/" + domainId + "/assets", "{\"name\":\"orders\",\"typeIdentifier\":"
                + "\"amazon.datazone.S3ObjectCollectionAssetType\",\"owningProjectIdentifier\":\"" + producer + "\"}")
                .statusCode(201).extract().path("id");
        String listingId = post("/v2/domains/" + domainId + "/listings/change-set", "{\"entityIdentifier\":\""
                + assetId + "\",\"entityType\":\"ASSET\",\"action\":\"PUBLISH\"}").statusCode(200).extract()
                .path("listingId");

        get("/v2/domains/" + domainId + "/subscription-requests").statusCode(400)
                .body("message", containsString("Specify one of"));
        String request = post("/v2/domains/" + domainId + "/subscription-requests", "{\"subscribedPrincipals\":"
                + "[{\"project\":{\"identifier\":\"" + consumer + "\"}}],\"subscribedListings\":[{\"identifier\":\""
                + listingId + "\"}],\"requestReason\":\"reporting\"}").statusCode(200)
                .body("status", equalTo("PENDING")).extract().path("id");
        get("/v2/domains/" + domainId + "/subscription-requests?approverProjectId=" + producer).statusCode(200)
                .body("items.id", hasItem(request));
        post("/v2/domains/" + domainId + "/subscription-requests", "{\"subscribedPrincipals\":[{\"project\":"
                + "{\"identifier\":\"" + consumer + "\"}}],\"subscribedListings\":[{\"identifier\":\"" + listingId
                + "\"}],\"requestReason\":\"again\"}").statusCode(409);
        String subscription = dz().body("{\"decisionComment\":\"ok\"}")
                .put("/v2/domains/" + domainId + "/subscription-requests/" + request + "/accept").then()
                .statusCode(200).body("status", equalTo("ACCEPTED")).extract().path("existingSubscriptionId");
        get("/v2/domains/" + domainId + "/subscriptions?owningProjectId=" + consumer).statusCode(200)
                .body("items.id", hasItem(subscription));
        dz().body("{}").put("/v2/domains/" + domainId + "/subscriptions/" + subscription + "/revoke").then()
                .statusCode(200).body("status", equalTo("REVOKED"));
        get("/v2/domains/" + domainId + "/notifications?type=EVENT").statusCode(200)
                .body("notifications.title", hasItem("SUBSCRIPTION_REQUEST_ACCEPTED"));
    }

    @Test
    void dataLakeEnvironmentImportsGlueTablesAndGrantsSubscriptions() {
        String domainId = domain("dz-env-" + suffix());
        String producer = project(domainId, "lake");
        String consumer = project(domainId, "analysts");
        String provisioning = role("dz-provisioning-" + suffix(), TRUST);
        String manageAccess = role("dz-manage-" + suffix(), TRUST);
        String bucket = "datazone-" + suffix();
        given().header("Authorization", auth("s3")).put("/" + bucket).then().statusCode(200);
        String blueprint = get("/v2/domains/" + domainId + "/environment-blueprints?name=DefaultDataLake")
                .statusCode(200).extract().path("items[0].id");
        dz().body("{\"enabledRegions\":[\"us-east-1\"],\"provisioningRoleArn\":\"" + provisioning
                        + "\",\"manageAccessRoleArn\":\"" + manageAccess + "\",\"regionalParameters\":{\"us-east-1\":"
                        + "{\"S3Location\":\"s3://" + bucket + "\"}}}")
                .put("/v2/domains/" + domainId + "/environment-blueprint-configurations/" + blueprint).then()
                .statusCode(200);

        String producerEnv = environment(domainId, producer, blueprint, "lake-env");
        String consumerEnv = environment(domainId, consumer, blueprint, "analyst-env");
        ValidatableResponse env = get("/v2/domains/" + domainId + "/environments/" + producerEnv).statusCode(200)
                .body("status", equalTo("ACTIVE")).body("lastDeployment.deploymentStatus", equalTo("SUCCESSFUL"));
        List<String> names = env.extract().path("provisionedResources.name");
        String database = env.extract().path("provisionedResources.find { it.name == 'glueProducerDBName' }.value");
        assertThat(names, hasItem("athenaWorkGroupName"));
        dz().get("/v2/domains/" + domainId + "/environments/" + producerEnv + "/credentials").then().statusCode(200)
                .body("accessKeyId", startsWith("ASIA"));

        // A Glue data source imports the producer database's tables as assets.
        glue("CreateTable", "{\"DatabaseName\":\"" + database + "\",\"TableInput\":{\"Name\":\"orders\","
                + "\"StorageDescriptor\":{\"Columns\":[{\"Name\":\"order_id\",\"Type\":\"string\"},"
                + "{\"Name\":\"total_amount\",\"Type\":\"double\"}]}}}");
        String source = post("/v2/domains/" + domainId + "/data-sources", "{\"name\":\"lake-source\",\"type\":"
                + "\"GLUE\",\"projectIdentifier\":\"" + producer + "\",\"environmentIdentifier\":\"" + producerEnv
                + "\",\"publishOnImport\":true,\"configuration\":{\"glueRunConfiguration\":"
                + "{\"relationalFilterConfigurations\":[{\"databaseName\":\"" + database + "\"}]}}}")
                .statusCode(201).body("status", equalTo("READY")).extract().path("id");
        String runId = post("/v2/domains/" + domainId + "/data-sources/" + source + "/runs", "{}").statusCode(200)
                .extract().path("id");
        get("/v2/domains/" + domainId + "/data-source-runs/" + runId).statusCode(200)
                .body("status", equalTo("SUCCESS")).body("runStatisticsForAssets.added", equalTo(1));
        get("/v2/domains/" + domainId + "/data-source-runs/" + runId + "/activities").statusCode(200)
                .body("items[0].dataAssetStatus", equalTo("SUCCEEDED_CREATED"));
        String rerun = post("/v2/domains/" + domainId + "/data-sources/" + source + "/runs", "{}").statusCode(200)
                .extract().path("id");
        get("/v2/domains/" + domainId + "/data-source-runs/" + rerun).statusCode(200)
                .body("runStatisticsForAssets.unchanged", equalTo(1));
        String listingId = post("/v2/domains/" + domainId + "/listings/search", "{\"searchText\":\"orders\"}")
                .statusCode(200).body("items[0].assetListing.entityType",
                        equalTo("amazon.datazone.GlueTableAssetType")).extract().path("items[0].assetListing.listingId");

        // The consumer environment's target grants the table through Lake Formation on approval.
        String consumerRole = get("/v2/domains/" + domainId + "/environments/" + consumerEnv).extract()
                .path("provisionedResources.find { it.name == 'userRoleArn' }.value");
        post("/v2/domains/" + domainId + "/environments/" + consumerEnv + "/subscription-targets", "{\"name\":"
                + "\"lake\",\"type\":\"GlueSubscriptionTargetType\",\"manageAccessRole\":\"" + manageAccess
                + "\",\"applicableAssetTypes\":[\"amazon.datazone.GlueTableAssetType\"],\"authorizedPrincipals\":[\""
                + consumerRole + "\"],\"subscriptionTargetConfig\":[]}").statusCode(200);
        String request = post("/v2/domains/" + domainId + "/subscription-requests", "{\"subscribedPrincipals\":"
                + "[{\"project\":{\"identifier\":\"" + consumer + "\"}}],\"subscribedListings\":[{\"identifier\":\""
                + listingId + "\"}],\"requestReason\":\"analysis\"}").statusCode(200).extract().path("id");
        String subscription = dz().body("{}").put("/v2/domains/" + domainId + "/subscription-requests/" + request
                + "/accept").then().statusCode(200).extract().path("existingSubscriptionId");
        get("/v2/domains/" + domainId + "/subscription-grants?subscriptionId=" + subscription).statusCode(200)
                .body("items[0].status", equalTo("COMPLETED")).body("items[0].assets[0].targetName",
                        equalTo("orders"));
        given().header("Authorization", auth("lakeformation")).contentType("application/json").body("{}")
                .post("/ListPermissions").then().statusCode(200)
                .body("PrincipalResourcePermissions.Principal.DataLakePrincipalIdentifier", hasItem(consumerRole));

        // A blueprint configuration in use cannot be removed.
        dz().delete("/v2/domains/" + domainId + "/environment-blueprint-configurations/" + blueprint).then()
                .statusCode(400).body("message", containsString("still referenced"));
    }

    private static String environment(String domainId, String projectId, String blueprint, String name) {
        String profile = post("/v2/domains/" + domainId + "/environment-profiles", "{\"name\":\"" + name
                + "-profile\",\"environmentBlueprintIdentifier\":\"" + blueprint + "\",\"projectIdentifier\":\""
                + projectId + "\",\"awsAccountId\":\"" + ACCOUNT + "\",\"awsAccountRegion\":\"us-east-1\"}")
                .statusCode(201).extract().path("id");
        return post("/v2/domains/" + domainId + "/environments", "{\"name\":\"" + name + "\",\"projectIdentifier\":\""
                + projectId + "\",\"environmentProfileIdentifier\":\"" + profile + "\"}").statusCode(201)
                .extract().path("id");
    }

    @Test
    void lineageEventsBuildAQueryableGraph() {
        String domainId = domain("dz-lineage-" + suffix());
        String event = "{\"eventType\":\"COMPLETE\",\"eventTime\":\"2026-01-01T00:00:00Z\",\"producer\":\"floci\","
                + "\"schemaURL\":\"https://openlineage.io/spec/2-0-2/OpenLineage.json\",\"run\":{\"runId\":\"r-1\"},"
                + "\"job\":{\"namespace\":\"etl\",\"name\":\"daily\"},\"inputs\":[{\"namespace\":\"s3://raw\","
                + "\"name\":\"orders\"}],\"outputs\":[{\"namespace\":\"glue\",\"name\":\"sales\"}]}";
        String eventId = dz().contentType("application/octet-stream").body(event.getBytes(StandardCharsets.UTF_8))
                .post("/v2/domains/" + domainId + "/lineage/events").then().statusCode(200).extract().path("id");
        dz().get("/v2/domains/" + domainId + "/lineage/events/" + eventId).then().statusCode(200)
                .header("Processing-Status", equalTo("SUCCESS"));
        ValidatableResponse input = dz().urlEncodingEnabled(false)
                .get("/v2/domains/" + domainId + "/lineage/nodes/s3:%2F%2Fraw%2Forders").then().statusCode(200).body("typeName", equalTo("amazon.datazone.DatasetType"))
                .body("downstreamNodes", hasSize(1));
        String inputId = input.extract().path("id");
        post("/v2/domains/" + domainId + "/graph/query", "{\"match\":[{\"entityPattern\":{\"entityType\":"
                + "\"LINEAGE_NODE\",\"identifier\":\"" + inputId + "\"}},{\"relationPattern\":{\"relationType\":"
                + "\"LINEAGE\",\"relationDirection\":\"OUT\",\"maxPathLength\":2}}]}").statusCode(200)
                .body("items", hasSize(3)).body("items.lineageNode.sourceIdentifier", hasItem("glue/sales"));
    }

    @Test
    void notebooksImportAndRunWithoutCompute() {
        String domainId = domain("dz-notebooks-" + suffix());
        String projectId = project(domainId, "science");
        String notebook = post("/v2/domains/" + domainId + "/notebooks", "{\"name\":\"explore\","
                + "\"owningProjectIdentifier\":\"" + projectId + "\"}").statusCode(201)
                .body("status", equalTo("ACTIVE")).extract().path("id");
        String bucket = "dz-nb-" + suffix();
        given().header("Authorization", auth("s3")).put("/" + bucket).then().statusCode(200);
        given().header("Authorization", auth("s3")).body("{\"cells\":[{},{}],\"nbformat\":4}".getBytes(
                StandardCharsets.UTF_8)).put("/" + bucket + "/nb.ipynb").then().statusCode(200);
        String imported = post("/v2/domains/" + domainId + "/notebook-imports", "{\"owningProjectIdentifier\":\""
                + projectId + "\",\"name\":\"imported\",\"sourceLocation\":{\"s3\":\"s3://" + bucket
                + "/nb.ipynb\"}}").statusCode(201).extract().path("notebookId");
        get("/v2/domains/" + domainId + "/notebooks/" + imported).statusCode(200).body("cellOrder", hasSize(2));
        String run = post("/v2/domains/" + domainId + "/notebook-runs", "{\"owningProjectIdentifier\":\""
                + projectId + "\",\"notebookIdentifier\":\"" + notebook + "\"}").statusCode(201).extract().path("id");
        get("/v2/domains/" + domainId + "/notebook-runs/" + run).statusCode(200).body("status", equalTo("FAILED"))
                .body("error.message", containsString("compute"));
        get("/v2/domains/" + domainId + "/notebooks?owningProjectIdentifier=" + projectId).statusCode(200)
                .body("items", hasSize(2));
    }
}
