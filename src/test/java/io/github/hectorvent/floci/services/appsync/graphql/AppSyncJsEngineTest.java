package io.github.hectorvent.floci.services.appsync.graphql;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AppSyncJsEngineTest {

    private final AppSyncJsEngine engine = new AppSyncJsEngine();

    @Test
    void requestAddsArguments() {
        String code = """
                export function request(ctx) {
                  return { payload: ctx.args.a + ctx.args.b };
                }
                export function response(ctx) {
                  return ctx.result;
                }
                """;
        Object result = engine.evaluate(code, "request", Map.of("arguments", Map.of("a", 2, "b", 3)));
        assertInstanceOf(Map.class, result);
        assertEquals(5.0, ((Map<?, ?>) result).get("payload"));
    }

    @Test
    void responseReturnsResult() {
        String code = """
                export function response(ctx) {
                  return ctx.result;
                }
                """;
        Object result = engine.evaluate(code, "response", Map.of("result", 5));
        assertEquals(5, result);
    }

    @Test
    void envAndPrevAreReadable() {
        String code = """
                export function response(ctx) {
                  return ctx.env.GREETING;
                }
                """;
        Object result = engine.evaluate(code, "response", Map.of("env", Map.of("GREETING", "hello from ctx.env")));
        assertEquals("hello from ctx.env", result);
    }
}
