package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes a REST JSON service's requests by botocore's HTTP bindings, vendored by
 * {@code tools/aws/regen_rest_json_bindings.py} under {@code aws/rest-json/<service>.json}.
 * <p>
 * A request matches the operation whose request URI it fits, the most specific one first (literal segments before
 * labels, labels before greedy labels). Its input is one JSON object: the body's members, with the members the model
 * binds to the URI, the query string, headers or a payload read from there and typed as the model types them, and an
 * absent idempotency token filled in. A handler returns the output object; members the model binds to headers or the
 * status code leave the body for there, a payload member becomes the body, and the response takes the operation's
 * modelled status code.
 */
public final class RestJsonDispatcher {

    /** Runs one operation: its name and its input, to its output. */
    @FunctionalInterface
    public interface Handler {
        ObjectNode handle(String operation, ObjectNode input);
    }

    /** A matched request: the operation and its assembled input. */
    public record Call(String operation, ObjectNode input) {}

    private record Route(String operation, JsonNode binding, Pattern pattern, Pattern lenient, Pattern spanning,
                         List<String> labels, List<String> queryLiterals, int[] ranks) {}

    private static final Pattern PATH_TOKEN = Pattern.compile("\\[]|\\{}|[^.\\[{]+");
    private static final Pattern LABEL = Pattern.compile("\\{([^}+]+)(\\+?)}");

    private final ObjectMapper mapper;
    private final List<Route> routes = new ArrayList<>();

    public RestJsonDispatcher(ObjectMapper mapper, String service) {
        this.mapper = mapper;
        String resource = "aws/rest-json/" + service + ".json";
        JsonNode model;
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("REST JSON bindings not found on the classpath: " + resource);
            }
            model = mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + resource, e);
        }
        model.path("operations").properties().forEach(entry -> routes.add(route(entry.getKey(), entry.getValue())));
        routes.sort(Comparator.comparing((Route route) -> route.ranks(), RestJsonDispatcher::compareRanks)
                .thenComparing(route -> -route.queryLiterals().size()));
    }

    private static Route route(String operation, JsonNode binding) {
        String uri = binding.path("requestUri").asText();
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        List<String> queryLiterals = new ArrayList<>();
        if (uri.contains("?")) {
            for (String part : uri.substring(uri.indexOf('?') + 1).split("&")) {
                if (!part.isEmpty()) {
                    queryLiterals.add(part);
                }
            }
        }
        StringBuilder regex = new StringBuilder("^");
        List<String> labels = new ArrayList<>();
        String[] segments = path.split("/", -1);
        int[] ranks = new int[Math.max(0, segments.length - 1)];
        for (int index = 1; index < segments.length; index++) {
            String segment = segments[index];
            regex.append('/');
            Matcher label = LABEL.matcher(segment);
            int last = 0;
            boolean hasLabel = false;
            boolean greedy = false;
            while (label.find()) {
                hasLabel = true;
                quote(regex, segment.substring(last, label.start()));
                greedy |= !label.group(2).isEmpty();
                regex.append(label.group(2).isEmpty() ? "([^/]+)" : "(.+)");
                labels.add(label.group(1));
                last = label.end();
            }
            quote(regex, segment.substring(last));
            boolean wholeLabel = segment.startsWith("{") && segment.endsWith("}")
                    && segment.indexOf('}') == segment.length() - 1;
            ranks[index - 1] = !hasLabel ? 3 : greedy ? 0 : wholeLabel ? 1 : 2;
        }
        regex.append("/?$");
        // Some clients leave a '/' in a final label unencoded (an ARN such as domain/dzd_1), and the server
        // decodes an encoded one: a second pass lets the last label span segments, and a third the labels before a
        // literal or another label (an ARN in /cluster/{clusterArn}/start or /policies/{arn}/{statementId}).
        String strict = regex.toString();
        int last = strict.lastIndexOf("([^/]+)");
        Pattern lenient = last >= 0 && strict.endsWith("([^/]+)/?$")
                ? Pattern.compile(strict.substring(0, last) + "(.+?)/?$") : null;
        Pattern spanning = strict.contains("([^/]+)") ? Pattern.compile(spanning(strict)) : null;
        return new Route(operation, binding, Pattern.compile(strict), lenient, spanning, labels, queryLiterals,
                ranks);
    }

    /**
     * The pattern whose labels span segments: each label but the last takes as much as it can, so an ARN before a
     * literal or another label keeps its slashes, and a last label that follows another label stays one segment.
     */
    private static String spanning(String strict) {
        int labels = strict.split("\\(\\[\\^/]\\+\\)", -1).length - 1;
        StringBuilder regex = new StringBuilder();
        int from = 0;
        for (int index = 0; index < labels; index++) {
            int at = strict.indexOf("([^/]+)", from);
            regex.append(strict, from, at).append(labels == 1 || index < labels - 1 ? "(.+)" : "([^/]+)");
            from = at + "([^/]+)".length();
        }
        return regex.append(strict.substring(from)).toString();
    }

    private static void quote(StringBuilder regex, String literal) {
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal));
        }
    }

    private static int compareRanks(int[] left, int[] right) {
        for (int index = 0; index < Math.min(left.length, right.length); index++) {
            if (left[index] != right[index]) {
                return Integer.compare(right[index], left[index]);
            }
        }
        return Integer.compare(right.length, left.length);
    }

    /** The operation and input a request is, or empty when no route matches it. */
    public Optional<Call> match(String method, String rawPath, MultivaluedMap<String, String> query,
                                          HttpHeaders headers, byte[] body) {
        for (int pass = 0; pass < 3; pass++) {
            for (Route route : routes) {
                Pattern pattern = pass == 0 ? route.pattern() : pass == 1 ? route.lenient() : route.spanning();
                if (pattern == null || !route.binding().path("method").asText().equalsIgnoreCase(method)) {
                    continue;
                }
                Matcher matcher = pattern.matcher(rawPath);
                if (!matcher.matches() || !queryMatches(route.queryLiterals(), query)
                        || (pass == 1 && earlierLabelIsArn(matcher))) {
                    continue;
                }
                ObjectNode input = input(route, matcher, query, headers, body);
                return Optional.of(new Call(route.operation(), input));
            }
        }
        return Optional.empty();
    }

    /** Dispatches a request to {@code handler}, answering with the operation's response. */
    public Response dispatch(String method, String rawPath, MultivaluedMap<String, String> query, HttpHeaders headers,
                             byte[] body, Handler handler) {
        Call call = match(method, rawPath, query, headers, body).orElseThrow(() -> new AwsException(
                "UnknownOperationException", "No operation matches " + method + " " + rawPath + ".", 404));
        ObjectNode output = handler.handle(call.operation(), call.input());
        return response(call.operation(), output == null ? mapper.createObjectNode() : output);
    }

    /**
     * Whether a label before the last holds an ARN: then the slash the last label would span belongs to the ARN
     * ({@code /policies/{arn}/{statementId}}), which the spanning pass reads.
     */
    private static boolean earlierLabelIsArn(Matcher matcher) {
        for (int group = 1; group < matcher.groupCount(); group++) {
            String value = matcher.group(group);
            if (value != null && AwsArnUtils.looksLikeArn(URLDecoder.decode(value.replace("+", "%2B"),
                    StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private static boolean queryMatches(List<String> literals, MultivaluedMap<String, String> query) {
        for (String literal : literals) {
            String name = literal.contains("=") ? literal.substring(0, literal.indexOf('=')) : literal;
            String value = literal.contains("=") ? literal.substring(literal.indexOf('=') + 1) : null;
            String actual = query == null ? null : query.getFirst(name);
            if (actual == null && (query == null || !query.containsKey(name))) {
                return false;
            }
            if (value != null && !value.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private ObjectNode input(Route route, Matcher matcher, MultivaluedMap<String, String> query, HttpHeaders headers,
                             byte[] body) {
        JsonNode members = route.binding().path("input").path("members");
        ObjectNode input = mapper.createObjectNode();
        String payloadMember = null;
        for (Map.Entry<String, JsonNode> entry : members.properties()) {
            if ("payload".equals(entry.getValue().path("location").asText())) {
                payloadMember = entry.getKey();
            }
        }
        if (body != null && body.length > 0) {
            if (payloadMember != null) {
                JsonNode spec = members.get(payloadMember);
                String type = spec.path("type").asText("string");
                if ("structure".equals(type)) {
                    input.set(payloadMember, parse(body));
                } else if ("blob".equals(type)) {
                    input.put(payloadMember, Base64.getEncoder().encodeToString(body));
                } else {
                    input.put(payloadMember, new String(body, StandardCharsets.UTF_8));
                }
            } else {
                JsonNode parsed = parse(body);
                if (parsed instanceof ObjectNode object) {
                    input.setAll(object);
                }
            }
        }
        for (Map.Entry<String, JsonNode> entry : members.properties()) {
            String name = entry.getKey();
            JsonNode spec = entry.getValue();
            String wire = spec.path("name").asText(name);
            switch (spec.path("location").asText()) {
                case "uri" -> {
                    int index = route.labels().indexOf(wire);
                    if (index >= 0) {
                        put(input, name, spec, URLDecoder.decode(matcher.group(index + 1).replace("+", "%2B"),
                                StandardCharsets.UTF_8));
                    }
                }
                case "querystring" -> {
                    List<String> values = query == null ? null : query.get(wire);
                    if ("map".equals(spec.path("type").asText()) && query != null) {
                        ObjectNode map = input.putObject(name);
                        query.forEach((key, list) -> {
                            if (!list.isEmpty() && members.properties().stream().noneMatch(other ->
                                    key.equals(other.getValue().path("name").asText(null)))) {
                                map.put(key, list.get(0));
                            }
                        });
                    } else if (values != null && !values.isEmpty()) {
                        if ("list".equals(spec.path("type").asText())) {
                            ArrayNode array = input.putArray(name);
                            for (String value : values) {
                                array.add(typed(spec.path("item").asText("string"), value));
                            }
                        } else {
                            put(input, name, spec, values.get(0));
                        }
                    }
                }
                case "header" -> {
                    String value = headers == null ? null : headers.getHeaderString(wire);
                    if (value != null) {
                        if ("list".equals(spec.path("type").asText())) {
                            ArrayNode array = input.putArray(name);
                            for (String item : value.split(",")) {
                                array.add(typed(spec.path("item").asText("string"), item.strip()));
                            }
                        } else {
                            put(input, name, spec, value);
                        }
                    }
                }
                case "headers" -> {
                    if (headers != null) {
                        ObjectNode map = input.putObject(name);
                        String prefix = wire.toLowerCase();
                        headers.getRequestHeaders().forEach((key, list) -> {
                            if (key.toLowerCase().startsWith(prefix) && !list.isEmpty()) {
                                map.put(key.substring(prefix.length()), list.get(0));
                            }
                        });
                    }
                }
                case "body" -> {
                    if (spec.path("idempotencyToken").asBoolean(false) && !input.hasNonNull(name)) {
                        input.put(name, UUID.randomUUID().toString());
                    }
                }
                default -> {
                }
            }
        }
        for (JsonNode path : route.binding().path("input").path("timestamps")) {
            convert(input, tokens(path.asText()), 0, true);
        }
        return input;
    }

    /**
     * Converts the timestamps at a path: on input, text (ISO 8601, an HTTP date or epoch seconds) to epoch
     * seconds, so handlers read every timestamp as a number; on output, epoch seconds to the ISO 8601 text the
     * model formats them as. A path's tokens are member names, {@code []} for each list element and {@code {}} for
     * each map value.
     */
    private void convert(JsonNode node, List<String> tokens, int index, boolean toSeconds) {
        if (node == null || index >= tokens.size()) {
            return;
        }
        String token = tokens.get(index);
        boolean last = index == tokens.size() - 1;
        if ("[]".equals(token) || "{}".equals(token)) {
            if (node instanceof ArrayNode array) {
                for (int position = 0; position < array.size(); position++) {
                    if (last) {
                        JsonNode converted = timestamp(array.get(position), toSeconds);
                        if (converted != null) {
                            array.set(position, converted);
                        }
                    } else {
                        convert(array.get(position), tokens, index + 1, toSeconds);
                    }
                }
            } else if (node instanceof ObjectNode object) {
                List<String> names = new ArrayList<>();
                object.fieldNames().forEachRemaining(names::add);
                for (String name : names) {
                    if (last) {
                        JsonNode converted = timestamp(object.get(name), toSeconds);
                        if (converted != null) {
                            object.set(name, converted);
                        }
                    } else {
                        convert(object.get(name), tokens, index + 1, toSeconds);
                    }
                }
            }
            return;
        }
        if (!(node instanceof ObjectNode object) || !object.has(token)) {
            return;
        }
        if (last) {
            JsonNode converted = timestamp(object.get(token), toSeconds);
            if (converted != null) {
                object.set(token, converted);
            }
            return;
        }
        convert(object.get(token), tokens, index + 1, toSeconds);
    }

    private static List<String> tokens(String path) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = PATH_TOKEN.matcher(path);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private JsonNode timestamp(JsonNode value, boolean toSeconds) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (toSeconds) {
            if (!value.isTextual()) {
                return null;
            }
            try {
                return mapper.getNodeFactory().numberNode(epochSeconds(value.asText().strip()));
            } catch (DateTimeParseException | NumberFormatException e) {
                throw new AwsException("ValidationException", "The value '" + value.asText()
                        + "' is not a valid timestamp.", 400);
            }
        }
        if (!value.isNumber()) {
            return null;
        }
        Instant instant = Instant.ofEpochMilli(Math.round(value.asDouble() * 1000));
        return mapper.getNodeFactory().textNode(DateTimeFormatter.ISO_INSTANT.format(instant));
    }

    private void put(ObjectNode input, String name, JsonNode spec, String value) {
        input.set(name, typed(spec.path("type").asText("string"), value));
    }

    private JsonNode typed(String type, String value) {
        try {
            return switch (type) {
                case "integer", "long" -> mapper.getNodeFactory().numberNode(Long.parseLong(value.strip()));
                case "double", "float" -> mapper.getNodeFactory().numberNode(Double.parseDouble(value.strip()));
                case "boolean" -> mapper.getNodeFactory().booleanNode(Boolean.parseBoolean(value.strip()));
                case "timestamp" -> mapper.getNodeFactory().numberNode(epochSeconds(value.strip()));
                default -> mapper.getNodeFactory().textNode(value);
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new AwsException("ValidationException", "The value '" + value + "' is not a valid " + type + ".",
                    400);
        }
    }

    /** A timestamp as epoch seconds, from ISO 8601, an HTTP date or epoch seconds. */
    private static double epochSeconds(String value) {
        if (value.matches("^-?\\d+(\\.\\d+)?$")) {
            return Double.parseDouble(value);
        }
        if (Character.isLetter(value.charAt(0))) {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() / 1000.0;
        }
        return OffsetDateTime.parse(value).toInstant().toEpochMilli() / 1000.0;
    }

    private static String headerValue(JsonNode spec, JsonNode value) {
        if (!"timestamp".equals(spec.path("type").asText()) || !value.isNumber()) {
            return value.isTextual() ? value.asText() : value.toString();
        }
        Instant instant = Instant.ofEpochMilli(Math.round(value.asDouble() * 1000));
        return switch (spec.path("format").asText("rfc822")) {
            case "iso8601" -> DateTimeFormatter.ISO_INSTANT.format(instant);
            case "unixTimestamp" -> value.asText();
            default -> DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atZone(ZoneOffset.UTC));
        };
    }

    private JsonNode parse(byte[] body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new AwsException("ValidationException", "The request body is not valid JSON.", 400);
        }
    }

    private Response response(String operation, ObjectNode output) {
        JsonNode binding = null;
        for (Route route : routes) {
            if (route.operation().equals(operation)) {
                binding = route.binding();
            }
        }
        int status = binding == null ? 200 : binding.path("responseCode").asInt(200);
        if (binding != null) {
            for (JsonNode path : binding.path("output").path("timestamps")) {
                convert(output, tokens(path.asText()), 0, false);
            }
        }
        Response.ResponseBuilder builder = null;
        Object entity = output;
        String contentType = MediaType.APPLICATION_JSON;
        if (binding != null) {
            List<String[]> headers = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : binding.path("output").path("members").properties()) {
                String name = entry.getKey();
                JsonNode spec = entry.getValue();
                JsonNode value = output.get(name);
                if (value == null || value.isNull()) {
                    continue;
                }
                switch (spec.path("location").asText()) {
                    case "header" -> {
                        headers.add(new String[] {spec.path("name").asText(name), headerValue(spec, value)});
                        output.remove(name);
                    }
                    case "headers" -> {
                        String prefix = spec.path("name").asText("");
                        value.properties().forEach(header -> headers.add(new String[] {prefix + header.getKey(),
                                header.getValue().asText()}));
                        output.remove(name);
                    }
                    case "statusCode" -> {
                        status = value.asInt(status);
                        output.remove(name);
                    }
                    case "payload" -> {
                        String type = spec.path("type").asText("string");
                        if ("blob".equals(type)) {
                            entity = value.isTextual() ? Base64.getDecoder().decode(value.asText()) : new byte[0];
                            contentType = MediaType.APPLICATION_OCTET_STREAM;
                        } else if ("structure".equals(type)) {
                            entity = value;
                        } else {
                            entity = value.asText();
                            contentType = MediaType.TEXT_PLAIN;
                        }
                        output.remove(name);
                    }
                    default -> {
                    }
                }
            }
            builder = Response.status(status);
            for (String[] header : headers) {
                builder.header(header[0], header[1]);
            }
        } else {
            builder = Response.status(status);
        }
        if (status == 204) {
            return builder.build();
        }
        return builder.entity(entity).type(contentType).build();
    }
}
