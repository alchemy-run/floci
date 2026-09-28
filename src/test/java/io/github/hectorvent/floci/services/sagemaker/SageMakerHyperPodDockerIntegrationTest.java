package io.github.hectorvent.floci.services.sagemaker;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * HyperPod nodes run their lifecycle scripts in real containers: a cluster is InService only
 * after every node's OnCreate script exited 0, and a failing script fails the cluster.
 */
@QuarkusTest
class SageMakerHyperPodDockerIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/sagemaker/aws4_request";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
             "Principal":{"Service":"sagemaker.amazonaws.com"},"Action":"sts:AssumeRole"}]}
            """;

    @Inject DockerClient dockerClient;
    @Inject S3Service s3Service;
    @Inject IamService iamService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for HyperPod node tests");
    }

    @Test
    void slurmClusterProvisionsNodesThroughLifecycleScriptsAndScalesUp() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String roleArn = role(suffix);
        String bucket = lifecycleBucket(suffix, "#!/bin/bash\nset -e\n"
                + "test -f /opt/ml/config/resource_config.json\n"
                + "grep -q \"$SAGEMAKER_INSTANCE_ID\" /opt/ml/config/resource_config.json\n"
                + "echo \"hyperpod on_create complete\"\n");
        String cluster = "hp-" + suffix;
        post("SageMaker.CreateCluster", clusterRequest(cluster, roleArn, bucket, 1)).then().statusCode(200);
        awaitStatus(cluster, "InService");

        post("SageMaker.UpdateCluster", """
                {"ClusterName":"%s","InstanceGroups":[%s]}
                """.formatted(cluster, group(roleArn, bucket, 2))).then().statusCode(200);
        awaitStatus(cluster, "InService");
        post("SageMaker.DescribeCluster", "{\"ClusterName\":\"%s\"}".formatted(cluster))
                .then().statusCode(200)
                .body("InstanceGroups[0].CurrentCount", equalTo(2))
                .body("InstanceGroups[0].TargetCount", equalTo(2));
        post("SageMaker.ListClusterNodes", "{\"ClusterName\":\"%s\"}".formatted(cluster))
                .then().statusCode(200)
                .body("ClusterNodeSummaries", hasSize(2))
                .body("ClusterNodeSummaries.InstanceStatus.Status", everyItem(equalTo("Running")));

        post("SageMaker.DeleteCluster", "{\"ClusterName\":\"%s\"}".formatted(cluster)).then().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() ->
                post("SageMaker.DescribeCluster", "{\"ClusterName\":\"%s\"}".formatted(cluster)).statusCode() == 400);
    }

    @Test
    void failingLifecycleScriptFailsTheCluster() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String roleArn = role(suffix);
        String bucket = lifecycleBucket(suffix, "#!/bin/bash\necho broken >&2\nexit 7\n");
        String cluster = "hp-fail-" + suffix;
        post("SageMaker.CreateCluster", clusterRequest(cluster, roleArn, bucket, 1)).then().statusCode(200);
        awaitStatus(cluster, "Failed");
        post("SageMaker.DescribeCluster", "{\"ClusterName\":\"%s\"}".formatted(cluster))
                .then().statusCode(200)
                .body("FailureMessage", containsString("exited with code 7"));
        post("SageMaker.DeleteCluster", "{\"ClusterName\":\"%s\"}".formatted(cluster)).then().statusCode(200);
    }

    private String role(String suffix) {
        return iamService.createRole("hyperpod-" + suffix, "/", TRUST, null, 0, null).getArn();
    }

    private String lifecycleBucket(String suffix, String onCreate) {
        String bucket = "sagemaker-hp-" + suffix;
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "lifecycle/on_create.sh", onCreate.getBytes(StandardCharsets.UTF_8),
                "text/x-shellscript", Map.of());
        return bucket;
    }

    private static String clusterRequest(String cluster, String roleArn, String bucket, int count) {
        return """
                {"ClusterName":"%s","NodeRecovery":"None","InstanceGroups":[%s]}
                """.formatted(cluster, group(roleArn, bucket, count));
    }

    private static String group(String roleArn, String bucket, int count) {
        return """
                {"InstanceGroupName":"controller","InstanceType":"ml.t3.medium","InstanceCount":%d,\
                "ExecutionRole":"%s","LifeCycleConfig":{"SourceS3Uri":"s3://%s/lifecycle","OnCreate":"on_create.sh"}}"""
                .formatted(count, roleArn, bucket);
    }

    private void awaitStatus(String cluster, String wanted) {
        String reached = await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofMillis(250))
                .until(() -> post("SageMaker.DescribeCluster", "{\"ClusterName\":\"%s\"}".formatted(cluster))
                        .then().statusCode(200).extract().<String>path("ClusterStatus"),
                        status -> wanted.equals(status) || "Failed".equals(status));
        if (!wanted.equals(reached)) {
            throw new AssertionError("Cluster " + cluster + " reached " + reached + ": "
                    + post("SageMaker.DescribeCluster", "{\"ClusterName\":\"%s\"}".formatted(cluster)).asString());
        }
    }

    private Response post(String target, String body) {
        return given().header("Authorization", AUTH).header("X-Amz-Target", target)
                .contentType("application/x-amz-json-1.1").body(body).when().post("/");
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
