package io.floci.aws.services.iotfleetwise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the messages and signals of a CAN DBC file into CAN signal decoders, as {@code ImportDecoderManifest} does:
 * each signal the import's {@code signalsMap} names becomes a decoder for the vehicle model signal it maps to.
 */
final class IotFleetWiseDbc {

    private static final Pattern MESSAGE = Pattern.compile("^BO_\\s+(\\d+)\\s+(\\w+)\\s*:");
    private static final Pattern SIGNAL = Pattern.compile("^SG_\\s+(\\w+)\\s*(?:[Mm]\\d*\\s*)?:\\s*(\\d+)\\|(\\d+)@"
            + "([01])([+-])\\s*\\(\\s*([-+0-9.eE]+)\\s*,\\s*([-+0-9.eE]+)\\s*\\)");
    private static final long EXTENDED_FRAME = 0x80000000L;

    private IotFleetWiseDbc() {
    }

    /** A DBC file from the request: base64 as JSON carries a blob. */
    static String text(JsonNode file) {
        String value = file.asText();
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    static List<JsonNode> decoders(ObjectMapper mapper, String dbc, String interfaceId, Map<String, String> map) {
        List<JsonNode> decoders = new ArrayList<>();
        long messageId = -1;
        boolean any = false;
        for (String raw : dbc.split("\\R")) {
            String line = raw.trim();
            Matcher message = MESSAGE.matcher(line);
            if (message.find()) {
                messageId = Long.parseLong(message.group(1)) & ~EXTENDED_FRAME;
                any = true;
                continue;
            }
            Matcher signal = SIGNAL.matcher(line);
            if (!signal.find() || messageId < 0) {
                continue;
            }
            String name = signal.group(1);
            String fqn = map.get(name);
            if (fqn == null) {
                fqn = map.entrySet().stream().filter(entry -> entry.getValue().equals(name)).map(Map.Entry::getKey)
                        .findFirst().orElse(null);
            }
            if (fqn == null) {
                continue;
            }
            ObjectNode decoder = mapper.createObjectNode();
            decoder.put("fullyQualifiedName", fqn).put("type", "CAN_SIGNAL").put("interfaceId", interfaceId);
            double factor = Double.parseDouble(signal.group(6));
            double offset = Double.parseDouble(signal.group(7));
            decoder.putObject("canSignal").put("messageId", messageId)
                    // DBC byte order 0 is Motorola (big-endian), 1 Intel (little-endian).
                    .put("isBigEndian", "0".equals(signal.group(4))).put("isSigned", "-".equals(signal.group(5)))
                    .put("startBit", Integer.parseInt(signal.group(2))).put("length", Integer.parseInt(signal.group(3)))
                    .put("factor", factor).put("offset", offset).put("name", name)
                    .put("signalValueType", factor == Math.rint(factor) && offset == Math.rint(offset)
                            ? "INTEGER" : "FLOATING_POINT");
            decoders.add(decoder);
        }
        if (!any) {
            throw IotFleetWiseStore.validation("The DBC file defines no messages.");
        }
        return decoders;
    }
}
