package io.github.hectorvent.floci.services.appsync.graphql.auth;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AppSyncAuthRequirements.AuthRequirement;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AppSyncAuthRequirements.DirectiveUse;
import io.github.hectorvent.floci.services.appsync.model.AuthenticationType;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class AuthorizationDataFetcher implements DataFetcher<Object> {

    private DataFetcher<?> delegate;
    private final String typeName;
    private final String fieldName;
    private final IamAuthValidator iamAuthValidator;

    public AuthorizationDataFetcher(DataFetcher<?> delegate, String typeName, String fieldName,
                                    IamAuthValidator iamAuthValidator) {
        this.delegate = delegate;
        this.typeName = typeName;
        this.fieldName = fieldName;
        this.iamAuthValidator = iamAuthValidator;
    }

    public void setDelegate(DataFetcher<?> delegate) {
        this.delegate = delegate;
    }

    public DataFetcher<?> getDelegate() {
        return delegate;
    }

    @Override
    public Object get(DataFetchingEnvironment environment) throws Exception {
        if (!authorize(environment)) {
            return DataFetcherResult.newResult()
                    .data(null)
                    .error(AppSyncFieldUnauthorizedException.from(environment, fieldName, typeName))
                    .build();
        }
        return delegate == null ? null : delegate.get(environment);
    }

    boolean authorize(DataFetchingEnvironment environment) {
        AppSyncAuthContext auth = environment.getGraphQlContext().get(AppSyncAuthContext.KEY);
        if (auth == null || auth.graphqlApi() == null) {
            return true;
        }
        if (isDeniedField(auth)) {
            return false;
        }
        if (!modeAllowed(environment, auth)) {
            return false;
        }
        if (auth.authenticationType() == AuthenticationType.AWS_IAM && iamAuthValidator != null) {
            String fieldArn = IamAuthValidator.fieldArn(
                    auth.region(), auth.accountId(), auth.graphqlApi().getApiId(), typeName, fieldName);
            if (iamAuthValidator.isFieldDenied(auth.accessKeyId(), fieldArn)) {
                return false;
            }
        }
        return true;
    }

    private boolean isDeniedField(AppSyncAuthContext auth) {
        Set<String> denied = auth.deniedFields();
        if (denied.isEmpty()) {
            return false;
        }
        String shortForm = typeName + "." + fieldName;
        if (denied.contains(shortForm)) {
            return true;
        }
        String thisArn = IamAuthValidator.fieldArn(
                auth.region(), auth.accountId(), auth.graphqlApi().getApiId(), typeName, fieldName);
        for (String entry : denied) {
            if (thisArn.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    private boolean modeAllowed(DataFetchingEnvironment environment, AppSyncAuthContext auth) {
        GraphqlApi api = auth.graphqlApi();
        List<AuthRequirement> requirements = effectiveRequirements(environment, api);
        if (requirements.isEmpty()) {
            return auth.authenticationType() == api.getAuthenticationType();
        }
        for (AuthRequirement requirement : requirements) {
            if (requirement.mode() == auth.authenticationType() && groupsAllowed(requirement, auth)) {
                return true;
            }
        }
        return false;
    }

    private List<AuthRequirement> effectiveRequirements(DataFetchingEnvironment environment, GraphqlApi api) {
        GraphQLFieldDefinition field = environment.getFieldDefinition();
        GraphQLObjectType parent = parentObjectType(environment);
        List<AuthRequirement> fieldReqs = requirementsFrom(field == null ? List.of() : field.getAppliedDirectives(), api);
        if (!fieldReqs.isEmpty()) {
            return fieldReqs;
        }
        return requirementsFrom(parent == null ? List.of() : parent.getAppliedDirectives(), api);
    }

    private static GraphQLObjectType parentObjectType(DataFetchingEnvironment environment) {
        Object parentType = environment.getParentType();
        if (parentType instanceof GraphQLObjectType objectType) {
            return objectType;
        }
        return null;
    }

    static List<AuthRequirement> requirementsFrom(List<GraphQLAppliedDirective> directives, GraphqlApi api) {
        List<DirectiveUse> uses = directives.stream()
                .map(directive -> new DirectiveUse(directive.getName(), groupsArg(directive)))
                .toList();
        return AppSyncAuthRequirements.requirementsFrom(uses, api);
    }

    @SuppressWarnings("unchecked")
    static List<String> groupsArg(GraphQLAppliedDirective directive) {
        GraphQLAppliedDirectiveArgument arg = directive.getArgument("cognito_groups");
        if (arg == null) {
            return List.of();
        }
        Object value = arg.getValue();
        if (value instanceof List<?> list) {
            List<String> groups = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    groups.add(String.valueOf(item));
                }
            }
            return groups;
        }
        return List.of();
    }

    static boolean groupsAllowed(AuthRequirement requirement, AppSyncAuthContext auth) {
        return AppSyncAuthRequirements.groupsAllowed(requirement, auth);
    }
}
