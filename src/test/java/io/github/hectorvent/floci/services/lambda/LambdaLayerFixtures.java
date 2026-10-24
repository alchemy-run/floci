package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.lambda.model.LambdaLayerVersion;

import java.util.Map;

public final class LambdaLayerFixtures {
    private LambdaLayerFixtures() {}

    public static LambdaLayerService accountScopedService(
            String defaultAccount, String region, Map<String, LambdaLayerVersion> layersByAccount) {
        AccountAwareStorageBackend<LambdaLayerVersion> storage = AccountAwareStorageBackend.inMemory(defaultAccount);
        layersByAccount.forEach((account, layer) -> storage.putForAccount(account,
                "layer::" + region + "::" + layer.getLayerName() + "::" + layer.getVersion(), layer));
        return new LambdaLayerService(new LambdaLayerStore(storage), null, null,
                new RegionResolver("us-east-1", defaultAccount), null);
    }

    public static LambdaLayerVersion layer(String account, String region, String name, long version) {
        LambdaLayerVersion layer = new LambdaLayerVersion();
        layer.setLayerName(name);
        layer.setVersion(version);
        layer.setLayerVersionArn("arn:aws:lambda:" + region + ":" + account + ":layer:" + name + ":" + version);
        return layer;
    }
}
