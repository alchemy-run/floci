package io.github.hectorvent.floci.testing;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EnforcementFixturesIntegrationTest {

    @TestHTTPResource("/")
    URI baseUri;

    @Test
    void removeBucketPurgesNullVersionVersionsAndMarkersAcrossPages() {
        String bucket = "fixture-purge-" + UUID.randomUUID();
        String authorization = auth("test", "s3");
        String key = "/" + bucket + "/first.txt";

        given().header("Authorization", authorization).when().put("/" + bucket)
                .then().statusCode(200);
        given().header("Authorization", authorization).body("before versioning")
                .when().put("/" + bucket + "/null.txt").then().statusCode(200);
        given().header("Authorization", authorization)
                .body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .queryParam("versioning", "")
                .when().put("/" + bucket).then().statusCode(200);
        for (int i = 0; i < 11; i++) {
            given().header("Authorization", authorization).body("version " + i)
                    .when().put(key).then().statusCode(200);
        }
        given().header("Authorization", authorization).when().delete(key).then().statusCode(204);
        given().header("Authorization", authorization).body("other key")
                .when().put("/" + bucket + "/second.txt").then().statusCode(200);
        given().urlEncodingEnabled(false).header("Authorization", authorization)
                .body("reserved key")
                .when().put("/" + bucket + "/dir/reserved%3Fhash%23%2Bpercent%25%20space.txt")
                .then().statusCode(200);

        given().header("Authorization", authorization).queryParam("versions", "")
                .queryParam("max-keys", 10)
                .when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<IsTruncated>true</IsTruncated>"))
                .body(containsString("<NextKeyMarker>first.txt</NextKeyMarker>"));
        given().header("Authorization", authorization).queryParam("versions", "")
                .queryParam("prefix", "null.txt")
                .when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<VersionId>null</VersionId>"));
        given().header("Authorization", authorization).queryParam("versions", "")
                .queryParam("prefix", "dir/")
                .when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<Key>dir/reserved?hash#+percent% space.txt</Key>"));

        EnforcementFixtures.removeBucket(baseUri.getPort(), authorization, bucket);

        given().header("Authorization", authorization).when().head("/" + bucket)
                .then().statusCode(404);
    }
}
