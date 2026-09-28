package io.github.hectorvent.floci.services.mwaa;

import io.github.hectorvent.floci.services.mwaa.model.CreateEnvironmentRequest;
import io.github.hectorvent.floci.services.mwaa.model.Environment;
import io.github.hectorvent.floci.services.mwaa.model.UpdateEnvironmentRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MWAA REST-JSON controller.
 *
 * <p>Like EKS, MWAA uses standard HTTP verbs with JSON bodies — not JSON 1.1 (X-Amz-Target)
 * or Query protocol. Paths and verbs mirror the real MWAA API exactly (see botocore's
 * {@code mwaa/2020-07-01/service-2.json}): {@code CreateEnvironment} is a {@code PUT}, not a
 * {@code POST}, and {@code UpdateEnvironment} is a {@code PATCH}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MwaaController {

    private final MwaaService mwaaService;
    private final MwaaRestApiInvoker restApiInvoker;

    @Inject
    public MwaaController(MwaaService mwaaService, MwaaRestApiInvoker restApiInvoker) {
        this.mwaaService = mwaaService;
        this.restApiInvoker = restApiInvoker;
    }

    @PUT
    @Path("/environments/{name}")
    public Response createEnvironment(@PathParam("name") String name, CreateEnvironmentRequest request) {
        Environment environment = mwaaService.createEnvironment(name, request);
        return Response.ok(Map.of("Arn", environment.getArn())).build();
    }

    @GET
    @Path("/environments/{name}")
    public Response getEnvironment(@PathParam("name") String name) {
        Environment environment = mwaaService.getEnvironment(name);
        return Response.ok(Map.of("Environment", environment)).build();
    }

    @GET
    @Path("/environments")
    public Response listEnvironments() {
        List<String> names = mwaaService.listEnvironments();
        return Response.ok(Map.of("Environments", names)).build();
    }

    @PATCH
    @Path("/environments/{name}")
    public Response updateEnvironment(@PathParam("name") String name, UpdateEnvironmentRequest request) {
        Environment environment = mwaaService.updateEnvironment(name, request);
        return Response.ok(Map.of("Arn", environment.getArn())).build();
    }

    @DELETE
    @Path("/environments/{name}")
    public Response deleteEnvironment(@PathParam("name") String name) {
        mwaaService.deleteEnvironment(name);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/webtoken/{name}")
    public Response createWebLoginToken(@PathParam("name") String name) {
        return Response.ok(mwaaService.createWebLoginToken(name)).build();
    }

    @POST
    @Path("/clitoken/{name}")
    public Response createCliToken(@PathParam("name") String name) {
        return Response.ok(mwaaService.createCliToken(name)).build();
    }

    @POST
    @Path("/restapi/{name}")
    public Response invokeRestApi(@PathParam("name") String name, Map<String, Object> request) {
        Map<String, Object> body = request != null ? request : Map.of();
        String path = body.get("Path") instanceof String p ? p : null;
        String method = body.get("Method") instanceof String m ? m : null;
        MwaaRestApiInvoker.validate(path, method);
        Environment environment = mwaaService.getEnvironment(name);
        MwaaRestApiInvoker.Result result = restApiInvoker.invoke(environment, mwaaService.isMockMode(),
                path, method, body.get("QueryParameters"), body.get("Body"));

        Map<String, Object> payload = new LinkedHashMap<>();
        if (result.isClientError() || result.isServerError()) {
            // Both modeled Airflow-error shapes are HTTP 400 in the MWAA API.
            String type = result.isClientError() ? "RestApiClientException" : "RestApiServerException";
            payload.put("__type", type);
            payload.put("message", "The Airflow REST API returned HTTP " + result.restApiStatusCode() + ".");
            payload.put("RestApiStatusCode", result.restApiStatusCode());
            payload.put("RestApiResponse", result.restApiResponse());
            return Response.status(400)
                    .header("X-Amzn-Errortype", type)
                    .entity(payload)
                    .build();
        }
        payload.put("RestApiStatusCode", result.restApiStatusCode());
        payload.put("RestApiResponse", result.restApiResponse());
        return Response.ok(payload).build();
    }
}
