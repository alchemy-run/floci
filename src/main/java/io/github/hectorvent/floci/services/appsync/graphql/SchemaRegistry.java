package io.github.hectorvent.floci.services.appsync.graphql;

import graphql.GraphQL;
import graphql.execution.AsyncExecutionStrategy;
import graphql.execution.AsyncSerialExecutionStrategy;
import graphql.execution.SubscriptionExecutionStrategy;
import graphql.schema.GraphQLSchema;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AuthFieldWrapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SchemaRegistry {
    private final Map<String, GraphQLSchema> schemas = new ConcurrentHashMap<>();
    private final Map<String, GraphQL> engines = new ConcurrentHashMap<>();
    private final AppSyncSchemaParser appSyncSchemaParser;
    private final AuthFieldWrapper authFieldWrapper;

    public SchemaRegistry(AppSyncSchemaParser appSyncSchemaParser) {
        this(appSyncSchemaParser, null);
    }

    private final AppSyncGraphqlExecutor resolverExecutor;

    public SchemaRegistry(AppSyncSchemaParser appSyncSchemaParser, AuthFieldWrapper authFieldWrapper) {
        this(appSyncSchemaParser, authFieldWrapper, null);
    }

    @Inject
    public SchemaRegistry(AppSyncSchemaParser appSyncSchemaParser, AuthFieldWrapper authFieldWrapper,
                          AppSyncGraphqlExecutor resolverExecutor) {
        this.appSyncSchemaParser = appSyncSchemaParser;
        this.authFieldWrapper = authFieldWrapper;
        this.resolverExecutor = resolverExecutor;
    }

    public void register(String apiId, String sdl) {
        GraphQLSchema schema = resolverExecutor == null
                ? appSyncSchemaParser.parse(sdl)
                : appSyncSchemaParser.parse(sdl, resolverExecutor.dataFetcher(apiId));
        if (authFieldWrapper != null) {
            schema = authFieldWrapper.wrap(schema);
        }
        schemas.put(apiId, schema);
        engines.put(apiId, buildGraphQL(schema));
    }

    public Optional<GraphQLSchema> getSchema(String apiId) {
        return Optional.ofNullable(schemas.get(apiId));
    }

    public Optional<GraphQL> getGraphQL(String apiId) {
        return Optional.ofNullable(engines.get(apiId));
    }

    public void remove(String apiId) {
        schemas.remove(apiId);
        engines.remove(apiId);
    }

    public static GraphQL buildGraphQL(GraphQLSchema schema) {
        return GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(new AsyncExecutionStrategy())
                .mutationExecutionStrategy(new AsyncSerialExecutionStrategy())
                .subscriptionExecutionStrategy(new SubscriptionExecutionStrategy())
                .build();
    }
}
