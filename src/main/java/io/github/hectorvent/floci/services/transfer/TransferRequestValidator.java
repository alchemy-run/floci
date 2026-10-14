package io.github.hectorvent.floci.services.transfer;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Collects Smithy constraint violations for one request and reports them the way AWS's JSON
 * services do: a single 400 {@code ValidationException} whose message lists every violation
 * ({@code "2 validation errors detected: Value 'x' at 'token' failed to satisfy constraint: ..."}).
 */
final class TransferRequestValidator {

    private final List<String> errors = new ArrayList<>();

    TransferRequestValidator required(String member, String value) {
        if (value == null) {
            errors.add("Value null at '" + member + "' failed to satisfy constraint: Member must not be null");
        }
        return this;
    }

    TransferRequestValidator length(String member, String value, int min, int max, boolean sensitive) {
        if (value == null) {
            return this;
        }
        if (value.length() < min) {
            errors.add(describe(member, value, sensitive)
                    + "Member must have length greater than or equal to " + min);
        } else if (value.length() > max) {
            errors.add(describe(member, value, sensitive)
                    + "Member must have length less than or equal to " + max);
        }
        return this;
    }

    TransferRequestValidator pattern(String member, String value, Pattern pattern, String displayed) {
        if (value != null && !pattern.matcher(value).matches()) {
            errors.add(describe(member, value, false)
                    + "Member must satisfy regular expression pattern: " + displayed);
        }
        return this;
    }

    TransferRequestValidator oneOf(String member, String value, Collection<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            errors.add(describe(member, value, false)
                    + "Member must satisfy enum value set: " + allowed);
        }
        return this;
    }

    void validate() {
        if (errors.isEmpty()) {
            return;
        }
        String prefix = errors.size() == 1
                ? "1 validation error detected: "
                : errors.size() + " validation errors detected: ";
        throw new AwsException("ValidationException", prefix + String.join("; ", errors), 400);
    }

    private static String describe(String member, String value, boolean sensitive) {
        String shown = sensitive ? "***" : value;
        return "Value '" + shown + "' at '" + member + "' failed to satisfy constraint: ";
    }
}
