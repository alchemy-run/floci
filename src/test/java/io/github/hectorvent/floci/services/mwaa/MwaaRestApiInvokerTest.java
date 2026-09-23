package io.github.hectorvent.floci.services.mwaa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.mwaa.model.Environment;
import io.github.hectorvent.floci.services.mwaa.model.EnvironmentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MwaaRestApiInvokerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MwaaRestApiInvoker invoker = new MwaaRestApiInvoker(mapper);

    private HttpServer airflow;
    private final AtomicReference<String> seenUri = new AtomicReference<>();
    private final AtomicReference<String> seenMethod = new AtomicReference<>();
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();

    @BeforeEach
    void startFakeAirflowApi() throws Exception {
        airflow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        airflow.createContext("/api/v1/", exchange -> {
            seenUri.set(exchange.getRequestURI().toString());
            seenMethod.set(exchange.getRequestMethod());
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean missing = exchange.getRequestURI().getPath().endsWith("/missing");
            byte[] response = (missing
                    ? "{\"title\":\"DAG not found\",\"status\":404}"
                    : "{\"dags\":[],\"total_entries\":0}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(missing ? 404 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        airflow.start();
    }

    @AfterEach
    void stopFakeAirflowApi() {
        airflow.stop(0);
    }

    private Environment availableEnvironment() {
        Environment environment = new Environment();
        environment.setName("env");
        environment.setStatus(EnvironmentStatus.AVAILABLE);
        environment.setAirflowVersion("2.10.5");
        environment.setAirflowInternalHost("127.0.0.1");
        environment.setAirflowInternalPort(airflow.getAddress().getPort());
        environment.setAirflowAdminPassword("s3cret");
        return environment;
    }

    @Test
    void relaysToAirflowRestApiAsAdminWithQueryAndBody() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("paused", false);
        query.put("tags", List.of("a", "b"));

        MwaaRestApiInvoker.Result result = invoker.invoke(availableEnvironment(), false,
                "/dags", "PATCH", query, Map.of("is_paused", true));

        assertEquals(200, result.restApiStatusCode());
        assertEquals(Map.of("dags", List.of(), "total_entries", 0), result.restApiResponse());
        assertEquals("/api/v1/dags?paused=false&tags=a&tags=b", seenUri.get());
        assertEquals("PATCH", seenMethod.get());
        assertEquals("Basic " + Base64.getEncoder().encodeToString(
                "admin:s3cret".getBytes(StandardCharsets.UTF_8)), seenAuth.get());
        assertEquals("{\"is_paused\":true}", seenBody.get());
    }

    @Test
    void airflowClientErrorsAreSurfacedWithTheirStatusAndBody() {
        MwaaRestApiInvoker.Result result = invoker.invoke(availableEnvironment(), false,
                "/dags/missing", "GET", null, null);

        assertTrue(result.isClientError());
        assertEquals(404, result.restApiStatusCode());
        assertEquals("DAG not found", ((Map<?, ?>) result.restApiResponse()).get("title"));
    }

    @Test
    void noWebserverMeansUnavailableInsteadOfAFabricatedAirflowReply() {
        MwaaRestApiInvoker.Result mock = invoker.invoke(availableEnvironment(), true, "/dags", "GET", null, null);
        assertTrue(mock.isServerError());
        assertEquals(503, mock.restApiStatusCode());
        assertEquals(null, seenUri.get());

        Environment creating = availableEnvironment();
        creating.setStatus(EnvironmentStatus.CREATING);
        assertEquals(503, invoker.invoke(creating, false, "/dags", "GET", null, null).restApiStatusCode());

        Environment airflow3 = availableEnvironment();
        airflow3.setAirflowVersion("3.0.6");
        assertEquals(503, invoker.invoke(airflow3, false, "/dags", "GET", null, null).restApiStatusCode());

        Environment unreachable = availableEnvironment();
        unreachable.setAirflowInternalPort(1);
        assertEquals(503, invoker.invoke(unreachable, false, "/dags", "GET", null, null).restApiStatusCode());
    }

    @Test
    void validateEnforcesPathAndMethodConstraints() {
        MwaaRestApiInvoker.validate("/dags", "GET");
        assertEquals("ValidationException",
                assertThrows(AwsException.class, () -> MwaaRestApiInvoker.validate(null, "GET")).getErrorCode());
        assertEquals("ValidationException",
                assertThrows(AwsException.class, () -> MwaaRestApiInvoker.validate("/" + "x".repeat(64), "GET")).getErrorCode());
        assertEquals("ValidationException",
                assertThrows(AwsException.class, () -> MwaaRestApiInvoker.validate("/dags", "HEAD")).getErrorCode());
    }
}
