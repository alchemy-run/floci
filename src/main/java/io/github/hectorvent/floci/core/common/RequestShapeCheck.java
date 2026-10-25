package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.RequestShapeModel.Shape;

import java.util.Map;
import java.util.Set;

/**
 * Walks a request value against a {@link RequestShapeModel} as AWS's JSON deserializers read a body,
 * before an operation validates anything, and refuses the first member of the wrong JSON type. The
 * walk is the same for every service: a JSON null is an absent member (a null list element is left
 * to the operation), a member the model does not know is skipped, a structure's values are read as
 * members and a list's or a map's as elements, a boolean also reads from "true" or "false" in any
 * case, and a scalar of the wrong kind is named by its JSON token ({@code NUMBER_VALUE},
 * {@code TRUE_VALUE}, ...). What a service's deserializer says at each point is its {@link Messages}.
 */
public final class RequestShapeCheck {

    /** A message that depends on whether the value was read as a member or as an element. */
    public record ByPosition(String member, String element) {

        public static ByPosition same(String message) {
            return new ByPosition(message, message);
        }

        String at(boolean asMember) {
            return asMember ? member : element;
        }
    }

    /**
     * One deserializer's wording, as measured on its service. A null message is answered with the
     * error code alone.
     *
     * @param errorCode          the exception every mismatch answers with, as HTTP 400
     * @param objectNotExpected  an object where a list or a scalar is expected
     * @param listNotExpected    a list where a structure, a map or a scalar is expected
     * @param scalarForStructure a scalar where a structure is expected
     * @param scalarForList      a scalar where a list is expected
     * @param scalarForMap       a scalar where a map is expected
     * @param conversion         a scalar of the wrong kind, formatted with its token and the target
     *                           type ({@code String}, {@code Integer}, {@code Long}, {@code Double},
     *                           {@code Date}, {@code Boolean}, {@code Blob})
     * @param decimalToken       whether a non-integral number is {@code DECIMAL_VALUE} rather than
     *                           {@code NUMBER_VALUE}
     * @param invalidBoolean     text other than true or false where a boolean is expected
     * @param blobNotText        a scalar that is not text where a blob is expected, or null to word
     *                           it as {@code conversion} does
     * @param scalarTypes        the scalar shape types the service was measured on; a value of any
     *                           other scalar type passes unchecked
     */
    public record Messages(String errorCode, String objectNotExpected, String listNotExpected,
                           ByPosition scalarForStructure, ByPosition scalarForList, ByPosition scalarForMap,
                           String conversion, boolean decimalToken, String invalidBoolean, String blobNotText,
                           Set<String> scalarTypes) {
    }

    private static final Map<String, String> TARGETS = Map.of("string", "String", "integer", "Integer",
            "long", "Long", "double", "Double", "timestamp", "Date", "boolean", "Boolean", "blob", "Blob");

    private RequestShapeCheck() {
    }

    /** Checks {@code value}, read as a member, against the shape {@code shapeName} of {@code model}. */
    public static void check(RequestShapeModel model, String shapeName, JsonNode value, Messages messages) {
        checkValue(model, model.shapes().get(shapeName), value, true, messages);
    }

    private static void checkValue(RequestShapeModel model, Shape shape, JsonNode value, boolean member,
                                   Messages messages) {
        if (value == null || value.isNull()) {
            return;
        }
        switch (shape.type()) {
            case "structure" -> checkStructure(model, shape, value, member, messages);
            case "list" -> checkList(model, shape, value, member, messages);
            case "map" -> checkMap(model, shape, value, member, messages);
            default -> checkScalar(shape.type(), value, messages);
        }
    }

    private static void checkStructure(RequestShapeModel model, Shape shape, JsonNode value, boolean member,
                                       Messages messages) {
        if (value.isArray()) {
            throw refusal(messages, messages.listNotExpected());
        }
        if (!value.isObject()) {
            throw refusal(messages, messages.scalarForStructure().at(member));
        }
        for (Map.Entry<String, JsonNode> field : value.properties()) {
            String memberShape = shape.members().get(field.getKey());
            if (memberShape != null) {
                checkValue(model, model.shapes().get(memberShape), field.getValue(), true, messages);
            }
        }
    }

    private static void checkList(RequestShapeModel model, Shape shape, JsonNode value, boolean member,
                                  Messages messages) {
        if (value.isObject()) {
            throw refusal(messages, messages.objectNotExpected());
        }
        if (!value.isArray()) {
            throw refusal(messages, messages.scalarForList().at(member));
        }
        checkElements(model, shape, value, messages);
    }

    private static void checkMap(RequestShapeModel model, Shape shape, JsonNode value, boolean member,
                                 Messages messages) {
        if (value.isArray()) {
            throw refusal(messages, messages.listNotExpected());
        }
        if (!value.isObject()) {
            throw refusal(messages, messages.scalarForMap().at(member));
        }
        checkElements(model, shape, value, messages);
    }

    private static void checkElements(RequestShapeModel model, Shape shape, JsonNode value, Messages messages) {
        Shape element = model.shapes().get(shape.element());
        for (JsonNode item : value) {
            checkValue(model, element, item, false, messages);
        }
    }

    private static void checkScalar(String type, JsonNode value, Messages messages) {
        if (!messages.scalarTypes().contains(type)) {
            return;
        }
        if (value.isObject()) {
            throw refusal(messages, messages.objectNotExpected());
        }
        if (value.isArray()) {
            throw refusal(messages, messages.listNotExpected());
        }
        switch (type) {
            case "string" -> requireToken(value.isTextual(), value, type, messages);
            case "integer", "long", "double", "timestamp" -> requireToken(value.isNumber(), value, type, messages);
            case "boolean" -> checkBoolean(value, messages);
            case "blob" -> checkBlob(value, messages);
            default -> {
            }
        }
    }

    private static void checkBoolean(JsonNode value, Messages messages) {
        if (value.isTextual()) {
            String text = value.textValue();
            if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                throw refusal(messages, messages.invalidBoolean());
            }
            return;
        }
        requireToken(value.isBoolean(), value, "boolean", messages);
    }

    private static void checkBlob(JsonNode value, Messages messages) {
        if (value.isTextual() || value.isBinary()) {
            return;
        }
        if (messages.blobNotText() != null) {
            throw refusal(messages, messages.blobNotText());
        }
        requireToken(false, value, "blob", messages);
    }

    private static void requireToken(boolean accepted, JsonNode value, String type, Messages messages) {
        if (!accepted) {
            throw refusal(messages, messages.conversion().formatted(token(value, messages), TARGETS.get(type)));
        }
    }

    private static String token(JsonNode value, Messages messages) {
        if (value.isNumber()) {
            return messages.decimalToken() && !value.isIntegralNumber() ? "DECIMAL_VALUE" : "NUMBER_VALUE";
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE";
        }
        return "STRING_VALUE";
    }

    private static AwsException refusal(Messages messages, String message) {
        return new AwsException(messages.errorCode(), message, 400);
    }
}
