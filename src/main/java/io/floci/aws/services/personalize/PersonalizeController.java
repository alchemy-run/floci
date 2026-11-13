package io.floci.aws.services.personalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RestJsonDispatcher;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;
import java.util.Set;

/**
 * The Personalize runtime ({@code /recommendations}, {@code /personalize-ranking}, {@code /action-recommendations})
 * and events ({@code /events}, {@code /items}, {@code /users}, {@code /actions}, {@code /action-interactions}) REST
 * APIs, routed here by {@link PersonalizeRouteFilter}.
 */
@Path(PersonalizeRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class PersonalizeController {

    private static final Set<String> EVENTS = Set.of("/events", "/items", "/users", "/actions",
            "/action-interactions");

    private static RequestConstraintCheck shapes(String resource, String label) {
        return RequestConstraintCheck.load(resource, label, new RequestConstraintCheck.Messages() {
            @Override
            public AwsException type(String path, String type) {
                return PersonalizeStore.invalid(path + " must be a " + type + ".");
            }

            @Override
            public AwsException bounds(String path, String type, Long min, Long max, long size) {
                String what = switch (type) {
                    case "string" -> "length";
                    case "integer", "long", "double", "float" -> "value";
                    default -> "size";
                };
                return PersonalizeStore.invalid(path + " has " + what + " " + size + ", outside "
                        + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
            }

            @Override
            public AwsException pattern(String path, String pattern) {
                return PersonalizeStore.invalid(path + " must match " + pattern + ".");
            }

            @Override
            public AwsException enumValue(String path, List<String> values) {
                return PersonalizeStore.invalid(path + " must be one of " + String.join(", ", values) + ".");
            }

            @Override
            public AwsException required(String path) {
                return PersonalizeStore.invalid(path + " is required.");
            }
        });
    }

    private static final RequestConstraintCheck RUNTIME_SHAPES = shapes("aws/personalizeruntime-request-shapes.json",
            "Personalize runtime request shapes");
    private static final RequestConstraintCheck EVENTS_SHAPES = shapes("aws/personalizeevents-request-shapes.json",
            "Personalize events request shapes");

    private final PersonalizeInference inference;
    private final RestJsonDispatcher runtime;
    private final RestJsonDispatcher events;

    @Inject
    public PersonalizeController(PersonalizeInference inference, ObjectMapper mapper) {
        this.inference = inference;
        this.runtime = new RestJsonDispatcher(mapper, "personalizeruntime");
        this.events = new RestJsonDispatcher(mapper, "personalizeevents");
    }

    @POST
    @Path("{path: .*}")
    public Response post(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        String path = uri.getRequestUri().getRawPath().substring(PersonalizeRouteFilter.PREFIX.length());
        boolean event = EVENTS.contains(path);
        return (event ? events : runtime).dispatch("POST", path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(event, operation, input));
    }

    private ObjectNode handle(boolean event, String operation, ObjectNode input) {
        (event ? EVENTS_SHAPES : RUNTIME_SHAPES).check(operation, input);
        return switch (operation) {
            case "GetRecommendations" -> inference.getRecommendations(input);
            case "GetPersonalizedRanking" -> inference.getPersonalizedRanking(input);
            case "GetActionRecommendations" -> inference.getActionRecommendations(input);
            case "PutEvents" -> inference.putEvents(input);
            case "PutItems" -> inference.putItems(input);
            case "PutUsers" -> inference.putUsers(input);
            case "PutActions" -> inference.putActions(input);
            case "PutActionInteractions" -> inference.putActionInteractions(input);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
