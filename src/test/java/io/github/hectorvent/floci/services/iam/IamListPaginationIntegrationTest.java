package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ListRoles and ListPolicies page through {@code MaxItems} and {@code Marker}. Both requests model
 * {@code MaxItems} with a range of 1 to 1000 and document that an omitted value "defaults to 100".
 *
 * <p>IAM state is shared across the suite, so each test scopes its listing to a path of its own
 * and removes what it created.
 */
@QuarkusTest
class IamListPaginationIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",\
            "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String POLICY_DOCUMENT = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject",\
            "Resource":"*"}]}""";

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private record Page(List<String> names, boolean truncated, String marker) {}

    private static Page page(String action, String listElement, String nameElement,
                             RequestSpecification request) {
        String result = action + "Response." + action + "Result.";
        XmlPath xml = request.when().post("/").then().statusCode(200).extract().xmlPath();
        String marker = xml.getString(result + "Marker");
        return new Page(xml.getList(result + listElement + ".member." + nameElement, String.class),
                Boolean.parseBoolean(xml.getString(result + "IsTruncated")),
                marker == null || marker.isEmpty() ? null : marker);
    }

    private static Page rolePage(String pathPrefix, String maxItems, String marker) {
        RequestSpecification request = iam("ListRoles").formParam("PathPrefix", pathPrefix);
        if (maxItems != null) {
            request.formParam("MaxItems", maxItems);
        }
        if (marker != null) {
            request.formParam("Marker", marker);
        }
        return page("ListRoles", "Roles", "RoleName", request);
    }

    private static Page policyPage(String scope, String pathPrefix, String maxItems, String marker) {
        RequestSpecification request = iam("ListPolicies").formParam("Scope", scope);
        if (pathPrefix != null) {
            request.formParam("PathPrefix", pathPrefix);
        }
        if (maxItems != null) {
            request.formParam("MaxItems", maxItems);
        }
        if (marker != null) {
            request.formParam("Marker", marker);
        }
        return page("ListPolicies", "Policies", "Arn", request);
    }

    /**
     * Follows {@code Marker} until {@code IsTruncated} is false, checking on every page that a
     * marker is present exactly when the page is truncated.
     */
    private static List<Page> walk(Function<String, Page> next) {
        List<Page> pages = new ArrayList<>();
        String marker = null;
        do {
            Page current = next.apply(marker);
            if (current.truncated()) {
                assertNotNull(current.marker(), "a truncated page carries a Marker");
            } else {
                assertNull(current.marker(), "the last page carries no Marker");
            }
            pages.add(current);
            marker = current.marker();
        } while (marker != null);
        return pages;
    }

    private static List<String> flatten(List<Page> pages) {
        List<String> all = new ArrayList<>();
        pages.forEach(p -> all.addAll(p.names()));
        return all;
    }

    private static void createRole(String name, String path) {
        iam("CreateRole").formParam("RoleName", name).formParam("Path", path)
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
        .when().post("/").then().statusCode(200);
    }

    private static void deleteRole(String name) {
        iam("DeleteRole").formParam("RoleName", name).when().post("/").then().statusCode(200);
    }

    private static String createPolicy(String name, String path) {
        return iam("CreatePolicy").formParam("PolicyName", name).formParam("Path", path)
            .formParam("PolicyDocument", POLICY_DOCUMENT)
        .when().post("/").then().statusCode(200)
            .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
    }

    private static void deletePolicy(String arn) {
        iam("DeletePolicy").formParam("PolicyArn", arn).when().post("/").then().statusCode(200);
    }

    @Test
    void listRolesPagesThroughThePathPrefixInNameOrder() {
        String tag = suffix();
        String path = "/pg-roles-" + tag + "/";
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add("pg-role-" + tag + "-" + i);
        }
        String outside = "pg-role-" + tag + "-outside";
        expected.forEach(name -> createRole(name, path));
        createRole(outside, "/pg-roles-other-" + tag + "/");
        try {
            List<Page> pages = walk(marker -> rolePage(path, "2", marker));

            assertEquals(List.of(2, 2, 1), pages.stream().map(p -> p.names().size()).toList());
            assertEquals(List.of(true, true, false), pages.stream().map(Page::truncated).toList());
            assertEquals(expected, flatten(pages), "every role once, in name order, nothing outside the prefix");
        } finally {
            expected.forEach(IamListPaginationIntegrationTest::deleteRole);
            deleteRole(outside);
        }
    }

    @Test
    void listRolesDefaultsToOneHundredItems() {
        String tag = suffix();
        String path = "/pg-roles-default-" + tag + "/";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            names.add(String.format("pg-default-%s-%03d", tag, i));
        }
        names.forEach(name -> createRole(name, path));
        try {
            Page first = rolePage(path, null, null);
            assertEquals(100, first.names().size());
            assertTrue(first.truncated());
            assertNotNull(first.marker());

            Page second = rolePage(path, null, first.marker());
            assertEquals(List.of(names.get(100)), second.names());
            assertFalse(second.truncated());
            assertNull(second.marker());
        } finally {
            names.forEach(IamListPaginationIntegrationTest::deleteRole);
        }
    }

    @Test
    void listPoliciesPagesThroughScopeAndPathPrefix() {
        String tag = suffix();
        String path = "/pg-policies-" + tag + "/";
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(createPolicy("pg-policy-" + tag + "-" + i, path));
        }
        String outside = createPolicy("pg-policy-" + tag + "-outside", "/pg-policies-other-" + tag + "/");
        try {
            List<Page> pages = walk(marker -> policyPage("Local", path, "2", marker));

            assertEquals(List.of(2, 2, 1), pages.stream().map(p -> p.names().size()).toList());
            assertEquals(List.of(true, true, false), pages.stream().map(Page::truncated).toList());
            assertEquals(expected, flatten(pages), "every policy once, in name order, nothing outside the prefix");
        } finally {
            expected.forEach(IamListPaginationIntegrationTest::deletePolicy);
            deletePolicy(outside);
        }
    }

    @Test
    void listPoliciesDefaultsToOneHundredAndWalksTheWholeCatalog() {
        Page first = policyPage("AWS", null, null, null);
        assertEquals(100, first.names().size(), "an omitted MaxItems defaults to 100");
        assertTrue(first.truncated());

        List<String> byDefault = flatten(walk(marker -> policyPage("AWS", null, null, marker)));
        List<String> byLargest = flatten(walk(marker -> policyPage("AWS", null, "1000", marker)));

        assertTrue(byDefault.size() > 100, "the AWS managed catalog is wider than one default page");
        assertEquals(byDefault.size(), new HashSet<>(byDefault).size(), "no policy repeats across pages");
        assertEquals(byLargest, byDefault, "the page size changes where pages split, not what they hold");
    }

    @Test
    void listRolesAndListPoliciesRejectMaxItemsOutsideOneToOneThousand() {
        for (String action : List.of("ListRoles", "ListPolicies")) {
            iam(action).formParam("MaxItems", "0")
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo(
                        "1 validation error detected: Value '0' at 'maxItems' failed to satisfy "
                                + "constraint: Member must have value greater than or equal to 1"));

            iam(action).formParam("MaxItems", "1001")
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo(
                        "1 validation error detected: Value '1001' at 'maxItems' failed to satisfy "
                                + "constraint: Member must have value less than or equal to 1000"));

            iam(action).formParam("MaxItems", "1000").when().post("/").then().statusCode(200);
        }
    }

    @Test
    void listRolesAndListPoliciesRejectAMarkerTheyDidNotIssue() {
        for (String action : List.of("ListRoles", "ListPolicies")) {
            iam(action).formParam("Marker", "not-a-marker")
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
        }
    }

    /**
     * The API reference documents Marker as the place "where the next call should start", so a
     * continuation returns the roles after the ones already seen, whatever happened ahead of it.
     */
    @Test
    void aRoleDeletedAheadOfTheMarkerSkipsNoRoleOnTheNextPage() {
        String tag = suffix();
        String path = "/pg-anchor-deleted-" + tag + "/";
        List<String> names = roleNames(tag, 5);
        names.forEach(name -> createRole(name, path));
        List<String> remaining = new ArrayList<>(names);
        try {
            Page first = rolePage(path, "2", null);
            assertEquals(names.subList(0, 2), first.names());

            deleteRole(names.getFirst());
            remaining.remove(names.getFirst());

            assertEquals(names.subList(2, 5), flatten(walk(marker -> rolePage(path, "2",
                    marker == null ? first.marker() : marker))));
        } finally {
            remaining.forEach(IamListPaginationIntegrationTest::deleteRole);
        }
    }

    @Test
    void aRoleCreatedAheadOfTheMarkerReturnsNoRoleTwice() {
        String tag = suffix();
        String path = "/pg-anchor-created-" + tag + "/";
        List<String> names = roleNames(tag, 5);
        names.subList(1, 5).forEach(name -> createRole(name, path));
        try {
            Page first = rolePage(path, "2", null);
            assertEquals(names.subList(1, 3), first.names());

            createRole(names.getFirst(), path);

            assertEquals(names.subList(3, 5), flatten(walk(marker -> rolePage(path, "2",
                    marker == null ? first.marker() : marker))));
        } finally {
            names.forEach(IamListPaginationIntegrationTest::deleteRole);
        }
    }

    @Test
    void theLastRoleOfAPageDeletedBeforeTheNextPageSkipsNoRole() {
        String tag = suffix();
        String path = "/pg-anchor-gone-" + tag + "/";
        List<String> names = roleNames(tag, 5);
        names.forEach(name -> createRole(name, path));
        List<String> remaining = new ArrayList<>(names);
        try {
            Page first = rolePage(path, "2", null);
            assertEquals(names.subList(0, 2), first.names());

            deleteRole(names.get(1));
            remaining.remove(names.get(1));

            assertEquals(names.subList(2, 5), flatten(walk(marker -> rolePage(path, "2",
                    marker == null ? first.marker() : marker))));
        } finally {
            remaining.forEach(IamListPaginationIntegrationTest::deleteRole);
        }
    }

    /**
     * ListPolicies lists customer-managed policies before the AWS-managed catalog. A marker naming
     * the last customer-managed policy, deleted before the next page, resumes at the first
     * AWS-managed one, although its name sorts after every managed policy's ARN.
     */
    @Test
    void aDeletedCustomerManagedPolicyResumesAtTheNextPolicyInListPoliciesOrder() {
        String name = "zzzz-pg-anchor-" + suffix();
        String arn = createPolicy(name, "/pg-anchor-policy/");
        boolean deleted = false;
        try {
            List<String> all = flatten(walk(marker -> policyPage("All", null, "1000", marker)));
            int index = all.indexOf(arn);
            assertTrue(index >= 0, "the created policy is listed");
            String following = all.get(index + 1);
            assertTrue(following.startsWith("arn:aws:iam::aws:policy/"),
                    "the policy is the last customer-managed one: " + following);

            Page first = policyPage("All", null, Integer.toString(index + 1), null);
            assertEquals(arn, first.names().getLast());

            deletePolicy(arn);
            deleted = true;

            assertEquals(List.of(following), policyPage("All", null, "1", first.marker()).names());
        } finally {
            if (!deleted) {
                deletePolicy(arn);
            }
        }
    }

    /** A marker shaped like an issued one but never issued is refused, as any other unissued marker is. */
    @Test
    void listRolesRejectsAnAnchoredMarkerItDidNotIssue() {
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("pg-anchor-forged.not-a-signature".getBytes(StandardCharsets.UTF_8));

        iam("ListRoles").formParam("Marker", forged)
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }

    private static List<String> roleNames(String tag, int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add("pg-anchor-" + tag + "-" + i);
        }
        return names;
    }
}
