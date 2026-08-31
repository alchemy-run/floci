package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.LambdaUrlConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaFunctionStoreUrlIndexTest {

    private static final String URL_ID = "abc123def456abc123def456abc123de";
    private static final String FUNCTION_URL =
            "http://" + URL_ID + ".lambda-url.us-east-1.localhost:4566/";

    @Test
    void getByUrlIdRebuildsIndexAfterClear() {
        LambdaFunctionStore store = new LambdaFunctionStore(new InMemoryStorage<>());
        store.save("us-east-1", functionWithUrl("fn"));
        store.clear();

        assertTrue(store.getByUrlId(URL_ID).isPresent());
        assertEquals("fn", store.getByUrlId(URL_ID).orElseThrow().getFunctionName());
    }

    @Test
    void savePreservesUrlConfigWhenOmittedOnUpdate() {
        LambdaFunctionStore store = new LambdaFunctionStore(new InMemoryStorage<>());
        store.save("us-east-1", functionWithUrl("fn"));

        LambdaFunction update = new LambdaFunction();
        update.setFunctionName("fn");
        update.setVersion("$LATEST");
        update.setRuntime("nodejs24.x");
        store.save("us-east-1", update);

        assertTrue(store.getByUrlId(URL_ID).isPresent());
        assertEquals(FUNCTION_URL,
                store.get("us-east-1", "fn").orElseThrow().getUrlConfig().getFunctionUrl());
    }

    @Test
    void savePreservesUrlConfigWhenUpdateHasEmptyUrlConfig() {
        LambdaFunctionStore store = new LambdaFunctionStore(new InMemoryStorage<>());
        store.save("us-east-1", functionWithUrl("fn"));

        LambdaFunction update = new LambdaFunction();
        update.setFunctionName("fn");
        update.setVersion("$LATEST");
        update.setUrlConfig(new LambdaUrlConfig());
        store.save("us-east-1", update);

        assertTrue(store.getByUrlId(URL_ID).isPresent());
        assertEquals(FUNCTION_URL,
                store.get("us-east-1", "fn").orElseThrow().getUrlConfig().getFunctionUrl());
    }

    @Test
    void getByUrlId_restoresFunctionIntoNewStoreAfterLiveReload() {
        LambdaFunctionStore previous = new LambdaFunctionStore(new InMemoryStorage<>());
        previous.save("us-east-1", functionWithUrl("fn"));

        // Simulate quarkus:dev reconstructing the bean against a fresh
        // in-memory backend while the process-level URL index survives.
        LambdaFunctionStore reloaded = new LambdaFunctionStore(new InMemoryStorage<>());
        assertTrue(reloaded.getByUrlId(URL_ID).isPresent());
        assertEquals("fn", reloaded.get("us-east-1", "fn").orElseThrow().getFunctionName());
        reloaded.clear();
    }

    private static LambdaFunction functionWithUrl(String name) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName(name);
        fn.setFunctionArn("arn:aws:lambda:us-east-1:000000000000:function:" + name);
        fn.setVersion("$LATEST");
        LambdaUrlConfig url = new LambdaUrlConfig();
        url.setFunctionUrl(FUNCTION_URL);
        fn.setUrlConfig(url);
        return fn;
    }
}
