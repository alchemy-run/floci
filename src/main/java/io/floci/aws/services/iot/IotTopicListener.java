package io.floci.aws.services.iot;

/**
 * A service that reads messages devices publish, as AWS services read their reserved topics: IoT Greengrass reads
 * the Fleet Status Service reports on {@code $aws/things/<thing>/greengrassv2/health/json}. Called for every publish,
 * from MQTT clients and the data plane alike, after the broker has taken it.
 */
public interface IotTopicListener {

    void onPublish(String topic, byte[] payload, String region);
}
