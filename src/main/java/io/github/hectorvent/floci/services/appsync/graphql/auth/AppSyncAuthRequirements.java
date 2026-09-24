package io.github.hectorvent.floci.services.appsync.graphql.auth;

import io.github.hectorvent.floci.services.appsync.model.AuthenticationType;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Interprets AppSync field directives for both {@link AuthorizationDataFetcher} and
 * {@link SidecarFieldAuthorizationPlanner}.
 */
final class AppSyncAuthRequirements {

    private AppSyncAuthRequirements() {
    }

    record AuthRequirement(AuthenticationType mode, List<String> groups) {
    }

    /** A directive application reduced to just what {@link #requirementsFrom} needs. */
    record DirectiveUse(String name, List<String> cognitoGroups) {
    }

    static List<AuthRequirement> requirementsFrom(List<DirectiveUse> directives, GraphqlApi api) {
        List<AuthRequirement> requirements = new ArrayList<>();
        boolean ignoreAwsAuth = AuthMiddleware.hasAdditionalModes(api);
        for (DirectiveUse directive : directives) {
            switch (directive.name()) {
                case "aws_api_key" -> requirements.add(new AuthRequirement(AuthenticationType.API_KEY, null));
                case "aws_iam" -> requirements.add(new AuthRequirement(AuthenticationType.AWS_IAM, null));
                case "aws_oidc" -> requirements.add(new AuthRequirement(AuthenticationType.OPENID_CONNECT, null));
                case "aws_lambda" -> requirements.add(new AuthRequirement(AuthenticationType.AWS_LAMBDA, null));
                case "aws_cognito_user_pools" -> requirements.add(
                        new AuthRequirement(AuthenticationType.AMAZON_COGNITO_USER_POOLS, directive.cognitoGroups()));
                case "aws_auth" -> {
                    if (!ignoreAwsAuth && api.getAuthenticationType() == AuthenticationType.AMAZON_COGNITO_USER_POOLS) {
                        requirements.add(new AuthRequirement(
                                AuthenticationType.AMAZON_COGNITO_USER_POOLS, directive.cognitoGroups()));
                    }
                }
                default -> {
                }
            }
        }
        return requirements;
    }

    static boolean groupsAllowed(AuthRequirement requirement, AppSyncAuthContext auth) {
        if (requirement.groups() == null || requirement.groups().isEmpty()) {
            return true;
        }
        if (auth.authenticationType() != AuthenticationType.AMAZON_COGNITO_USER_POOLS) {
            return true;
        }
        Object groups = auth.identity() == null ? null : auth.identity().get("groups");
        if (!(groups instanceof List<?> callerGroups)) {
            return false;
        }
        for (String required : requirement.groups()) {
            for (Object caller : callerGroups) {
                if (required.equals(String.valueOf(caller))) {
                    return true;
                }
            }
        }
        return false;
    }
}
