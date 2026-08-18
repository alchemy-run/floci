package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLSchema;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.FunctionConfiguration;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import io.github.hectorvent.floci.services.appsync.model.ResolverKind;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AppSyncGraphqlExecutor {
    private static final Logger LOG = Logger.getLogger(AppSyncGraphqlExecutor.class);

    private final StorageBackend<String, GraphqlApi> apiStore;
    private final StorageBackend<String, String> schemaStore;
    private final StorageBackend<String, Resolver> resolverStore;
    private final StorageBackend<String, FunctionConfiguration> functionStore;
    private final StorageBackend<String, DataSource> dataSourceStore;
    private final AppSyncSchemaParser schemaParser;
    private final AppSyncJsEngine jsEngine;
    private final AppSyncVtlEngine vtlEngine;
    private final Instance<LambdaService> lambdaService;
    private final ObjectMapper objectMapper;

    @Inject
    public AppSyncGraphqlExecutor(StorageFactory storageFactory,
                                  StorageBackend<String, String> schemaStore,
                                  AppSyncSchemaParser schemaParser,
                                  AppSyncJsEngine jsEngine,
                                  AppSyncVtlEngine vtlEngine,
                                  Instance<LambdaService> lambdaService,
                                  ObjectMapper objectMapper) {
        this.apiStore = storageFactory.create("appsync", "appsync-apis.json", new TypeReference<>() {});
        this.schemaStore = schemaStore;
        this.resolverStore = storageFactory.create("appsync", "appsync-resolvers.json", new TypeReference<>() {});
        this.functionStore = storageFactory.create("appsync", "appsync-functions.json", new TypeReference<>() {});
        this.dataSourceStore = storageFactory.create("appsync", "appsync-datasources.json", new TypeReference<>() {});
        this.schemaParser = schemaParser;
        this.jsEngine = jsEngine;
        this.vtlEngine = vtlEngine;
        this.lambdaService = lambdaService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> execute(String apiId, String query, Map<String, Object> variables, String operationName) {
        GraphqlApi api = apiStore.get(apiId)
                .orElseThrow(() -> new AwsException("NotFoundException", "GraphQL API not found: " + apiId, 404));
        String sdl = schemaStore.get(apiId)
                .orElseThrow(() -> new AwsException("NotFoundException", "Schema not found for API: " + apiId, 404));

        DataFetcher<?> fetcher = env -> resolveField(api, env);
        GraphQLSchema schema = schemaParser.parse(sdl, fetcher);
        GraphQL graphQL = GraphQL.newGraphQL(schema).build();
        ExecutionResult result = graphQL.execute(ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables != null ? variables : Map.of())
                .operationName(operationName)
                .build());
        return result.toSpecification();
    }

    private Object resolveField(GraphqlApi api, DataFetchingEnvironment env) {
        String typeName = ((GraphQLNamedType) env.getParentType()).getName();
        String fieldName = env.getField().getName();
        Resolver resolver = resolverStore.get(api.getApiId() + "::" + typeName + "::" + fieldName).orElse(null);
        if (resolver == null) {
            return null;
        }
        Map<String, Object> stash = new LinkedHashMap<>();
        Map<String, Object> ctx = baseContext(api, env, stash, null, null);
        if (resolver.getKind() == ResolverKind.PIPELINE) {
            return resolvePipeline(api, resolver, env, stash, ctx);
        }
        return resolveUnit(api, resolver.getDataSourceName(), resolver.getCode(),
                resolver.getRequestMappingTemplate(), resolver.getResponseMappingTemplate(), ctx);
    }

    @SuppressWarnings("unchecked")
    private Object resolvePipeline(GraphqlApi api, Resolver resolver, DataFetchingEnvironment env,
                                   Map<String, Object> stash, Map<String, Object> ctx) {
        evaluateHandler(resolver.getCode(), resolver.getRequestMappingTemplate(), "request", ctx);
        Object prev = null;
        List<String> functionIds = List.of();
        if (resolver.getPipelineConfig() instanceof Map<?, ?> config) {
            Object functions = config.get("functions");
            if (functions instanceof List<?> list) {
                functionIds = list.stream().map(String::valueOf).toList();
            }
        }
        for (String functionId : functionIds) {
            FunctionConfiguration fn = functionStore.get(api.getApiId() + "::" + functionId)
                    .orElseThrow(() -> new AwsException("NotFoundException", "Function not found: " + functionId, 404));
            Map<String, Object> stepCtx = baseContext(api, env, stash, prev, null);
            prev = resolveUnit(api, fn.getDataSourceName(), fn.getCode(),
                    fn.getRequestMappingTemplate(), fn.getResponseMappingTemplate(), stepCtx);
        }
        Map<String, Object> prevMap = new LinkedHashMap<>();
        prevMap.put("result", prev);
        Map<String, Object> responseCtx = baseContext(api, env, stash, prev, prev);
        responseCtx.put("prev", prevMap);
        Object result = evaluateHandler(resolver.getCode(), resolver.getResponseMappingTemplate(), "response", responseCtx);
        return coerceGraphql(result);
    }

    @SuppressWarnings("unchecked")
    private Object resolveUnit(GraphqlApi api, String dataSourceName, String code,
                               String requestTemplate, String responseTemplate, Map<String, Object> ctx) {
        Object request = evaluateHandler(code, requestTemplate, "request", ctx);
        Object datasourceResult = invokeDataSource(api, dataSourceName, request);
        ctx.put("result", datasourceResult);
        Object response = evaluateHandler(code, responseTemplate, "response", ctx);
        return coerceGraphql(response);
    }

    @SuppressWarnings("unchecked")
    private Object invokeDataSource(GraphqlApi api, String dataSourceName, Object request) {
        if (dataSourceName == null || dataSourceName.isBlank()) {
            return payloadOf(request);
        }
        DataSource ds = dataSourceStore.get(api.getApiId() + "::" + dataSourceName).orElse(null);
        if (ds == null || ds.getType() == null) {
            return payloadOf(request);
        }
        return switch (ds.getType()) {
            case NONE -> payloadOf(request);
            case AWS_LAMBDA -> invokeLambda(ds, request);
            default -> payloadOf(request);
        };
    }

    @SuppressWarnings("unchecked")
    private Object invokeLambda(DataSource ds, Object request) {
        if (lambdaService.isUnsatisfied()) {
            throw new AwsException("InternalFailureException", "Lambda service is not available", 500);
        }
        Map<String, Object> lambdaConfig = ds.getLambdaConfig();
        String functionArn = lambdaConfig == null ? null : String.valueOf(lambdaConfig.get("lambdaFunctionArn"));
        if (functionArn == null || functionArn.isBlank() || "null".equals(functionArn)) {
            throw new AwsException("BadRequestException", "Lambda data source is missing lambdaFunctionArn", 400);
        }
        Object payload = request;
        if (request instanceof Map<?, ?> map) {
            payload = map.containsKey("payload") ? map.get("payload") : request;
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(payload == null ? Map.of() : payload);
            String[] parts = functionArn.split(":");
            String region = parts.length > 3 ? parts[3] : "us-east-1";
            String functionName = parts.length > 6 ? parts[6] : functionArn;
            InvokeResult result = lambdaService.get().invoke(region, functionName, body, InvocationType.RequestResponse);
            if (result.getFunctionError() != null) {
                throw new AwsException("InternalFailureException",
                        "Lambda invocation failed: " + result.getFunctionError(), 500);
            }
            byte[] response = result.getPayload();
            if (response == null || response.length == 0) {
                return null;
            }
            String text = new String(response, StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(text, Object.class);
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnv(e, "Lambda data source invocation failed");
            throw new AwsException("InternalFailureException", "Lambda invocation failed: " + e.getMessage(), 500);
        }
    }

    private Object evaluateHandler(String code, String vtlTemplate, String function, Map<String, Object> ctx) {
        if (code != null && !code.isBlank()) {
            return jsEngine.evaluate(code, function, ctx);
        }
        if (vtlTemplate != null && !vtlTemplate.isBlank()) {
            AppSyncVtlContext vtl = AppSyncVtlContext.builder(objectMapper)
                    .arguments(asMap(ctx.get("arguments")))
                    .source(asMap(ctx.get("source")))
                    .identity(asMap(ctx.get("identity")))
                    .request(asMap(ctx.get("request")))
                    .info(asMap(ctx.get("info")))
                    .stash(asMap(ctx.get("stash")))
                    .prev(asMap(ctx.get("prev")))
                    .result(ctx.get("result"))
                    .env(asMap(ctx.get("env")))
                    .build();
            AppSyncVtlResult result = vtlEngine.evaluate(vtlTemplate, vtl);
            if (result.hasError()) {
                throw new AwsException("BadRequestException", result.error().getMessage(), 400);
            }
            Object output = result.output();
            if (output instanceof String s) {
                String trimmed = s.strip();
                if (trimmed.isEmpty()) {
                    return null;
                }
                try {
                    return objectMapper.readValue(trimmed, Object.class);
                } catch (Exception ignored) {
                    return trimmed;
                }
            }
            return output;
        }
        return null;
    }

    private Map<String, Object> baseContext(GraphqlApi api, DataFetchingEnvironment env,
                                            Map<String, Object> stash, Object prev, Object result) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("fieldName", env.getField().getName());
        info.put("parentTypeName", ((GraphQLNamedType) env.getParentType()).getName());
        info.put("variables", env.getVariables());
        Map<String, Object> prevMap = prev == null ? null : Map.of("result", prev);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("arguments", env.getArguments());
        ctx.put("args", env.getArguments());
        Object source = env.getSource();
        ctx.put("source", source instanceof Map<?, ?> map ? map : Map.of());
        ctx.put("info", info);
        ctx.put("stash", stash);
        ctx.put("prev", prevMap);
        ctx.put("result", result);
        ctx.put("env", api.getEnvironmentVariables() != null ? api.getEnvironmentVariables() : Map.of());
        ctx.put("identity", Map.of());
        ctx.put("request", Map.of("headers", Map.of()));
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Object payloadOf(Object request) {
        if (request instanceof Map<?, ?> map && map.containsKey("payload")) {
            return map.get("payload");
        }
        return request;
    }

    private static Object coerceGraphql(Object value) {
        if (value instanceof Double d && d == Math.rint(d) && !d.isNaN() && !d.isInfinite()) {
            long whole = d.longValue();
            if (whole >= Integer.MIN_VALUE && whole <= Integer.MAX_VALUE) {
                return (int) whole;
            }
            return whole;
        }
        return value;
    }
}
