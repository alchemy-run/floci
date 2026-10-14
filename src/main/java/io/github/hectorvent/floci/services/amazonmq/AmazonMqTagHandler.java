package io.github.hectorvent.floci.services.amazonmq;

import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.common.V1Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Amazon MQ's CreateTags/DeleteTags/ListTags, which AWS puts on {@code /v1/tags/{resource-arn}}
 * for both broker and configuration ARNs.
 *
 * <p>A separate bean for the same reason as {@code MskTagHandler}: a class-level {@link V1Tags}
 * on a service would strip its {@code @Default} qualifier. The MQ wire shape (lowercase
 * {@code tags} map, POST, {@code tagKeys}, 204) matches every {@link TagHandler} default.
 */
@ApplicationScoped
@V1Tags
public class AmazonMqTagHandler implements TagHandler {

    private final AmazonMqService brokers;
    private final AmazonMqConfigurationService configurations;

    @Inject
    public AmazonMqTagHandler(AmazonMqService brokers, AmazonMqConfigurationService configurations) {
        this.brokers = brokers;
        this.configurations = configurations;
    }

    @Override
    public String serviceKey() {
        return "mq";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return configurations.isConfigurationArn(arn)
                ? configurations.listTags(arn)
                : brokers.listBrokerTags(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (configurations.isConfigurationArn(arn)) {
            configurations.tagResource(arn, tags);
        } else {
            brokers.tagBroker(arn, tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (configurations.isConfigurationArn(arn)) {
            configurations.untagResource(arn, tagKeys);
        } else {
            brokers.untagBroker(arn, tagKeys);
        }
    }
}
