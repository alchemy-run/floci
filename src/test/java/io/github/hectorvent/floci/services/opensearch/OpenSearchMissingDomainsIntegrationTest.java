package io.github.hectorvent.floci.services.opensearch;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;

@QuarkusTest
class OpenSearchMissingDomainsIntegrationTest {
    @Test
    void describeDomainsOmitsUnknownNames() {
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260921/us-east-1/es/aws4_request, SignedHeaders=host, Signature=test")
                .contentType("application/json")
                .body("{\"DomainNames\":[\"missing-domains-contract\",\"missing-domains-contract\"]}")
                .post("/2021-01-01/opensearch/domain-info")
                .then().statusCode(200).body("DomainStatusList", empty());
    }
}
