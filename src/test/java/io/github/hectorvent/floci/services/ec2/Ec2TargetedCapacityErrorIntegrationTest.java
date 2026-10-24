package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CapacityReservationSpecification;
import software.amazon.awssdk.services.ec2.model.CapacityReservationTarget;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Subnet;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class Ec2TargetedCapacityErrorIntegrationTest {

    @Test
    void sdkReceivesCapacityErrorAndCanRetryAfterRelease() {
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("741623400002", "test"))).build()) {
            Subnet subnet = client.describeSubnets().subnets().getFirst();
            String capacityId = client.createCapacityReservation(request -> request.instanceType("t3.micro")
                    .instancePlatform("Linux/UNIX").availabilityZone(subnet.availabilityZone())
                    .instanceMatchCriteria("targeted").instanceCount(1))
                    .capacityReservation().capacityReservationId();
            List<String> instances = new ArrayList<>();
            try {
                RunInstancesRequest request = RunInstancesRequest.builder().imageId("ami-test")
                        .instanceType("t3.micro").minCount(1).maxCount(1)
                        .subnetId(subnet.subnetId())
                        .capacityReservationSpecification(CapacityReservationSpecification.builder()
                                .capacityReservationTarget(CapacityReservationTarget.builder()
                                        .capacityReservationId(capacityId).build()).build()).build();
                instances.add(client.runInstances(request).instances().getFirst().instanceId());
                Ec2Exception failure = assertThrows(Ec2Exception.class, () -> client.runInstances(request));
                assertEquals("ReservationCapacityExceeded", failure.awsErrorDetails().errorCode());
                assertEquals(400, failure.statusCode());
                assertEquals(0, client.describeCapacityReservations(describe ->
                        describe.capacityReservationIds(capacityId)).capacityReservations()
                        .getFirst().availableInstanceCount());
                client.terminateInstances(terminate -> terminate.instanceIds(instances));
                instances.add(client.runInstances(request).instances().getFirst().instanceId());
                assertEquals(2, instances.size());
            } finally {
                if (!instances.isEmpty()) {
                    client.terminateInstances(terminate -> terminate.instanceIds(instances));
                }
                client.cancelCapacityReservation(cancel -> cancel.capacityReservationId(capacityId));
            }
        }
    }
}
