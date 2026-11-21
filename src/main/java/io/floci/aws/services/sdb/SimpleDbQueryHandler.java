package io.floci.aws.services.sdb;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.XmlBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Query protocol for Amazon SimpleDB ({@code Action=…}, signed for {@code sdb}). Responses carry SimpleDB's own
 * metadata ({@code RequestId} and {@code BoxUsage}) and errors come in its {@code Response/Errors/Error} form.
 */
@ApplicationScoped
public class SimpleDbQueryHandler {

    private static final Logger LOG = Logger.getLogger(SimpleDbQueryHandler.class);
    public static final String NS = "http://sdb.amazonaws.com/doc/2009-04-15/";
    private static final String BOX_USAGE = "0.0000219907";

    private final SimpleDbService service;

    @Inject
    public SimpleDbQueryHandler(SimpleDbService service) {
        this.service = service;
    }

    public Response handle(String action, MultivaluedMap<String, String> p, String region) {
        try {
            return switch (action) {
                case "CreateDomain" -> {
                    service.createDomain(region, p.getFirst("DomainName"));
                    yield ok(action, null);
                }
                case "DeleteDomain" -> {
                    service.deleteDomain(region, p.getFirst("DomainName"));
                    yield ok(action, null);
                }
                case "ListDomains" -> listDomains(p, region);
                case "DomainMetadata" -> domainMetadata(p, region);
                case "PutAttributes" -> {
                    service.putAttributes(region, p.getFirst("DomainName"), p.getFirst("ItemName"),
                            changes(p, "Attribute", true), expected(p));
                    yield ok(action, null);
                }
                case "BatchPutAttributes" -> {
                    service.batchPut(region, p.getFirst("DomainName"), batch(p, true));
                    yield ok(action, null);
                }
                case "DeleteAttributes" -> {
                    service.deleteAttributes(region, p.getFirst("DomainName"), p.getFirst("ItemName"),
                            changes(p, "Attribute", false), expected(p));
                    yield ok(action, null);
                }
                case "BatchDeleteAttributes" -> {
                    service.batchDelete(region, p.getFirst("DomainName"), batch(p, false));
                    yield ok(action, null);
                }
                case "GetAttributes" -> getAttributes(p, region);
                case "Select" -> select(p, region);
                default -> error("InvalidAction", "The action " + action + " is not valid for this web service.",
                        400);
            };
        } catch (AwsException e) {
            return error(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        } catch (Exception e) {
            LOG.errorv(e, "Unexpected error in SimpleDB {0}", action);
            return error("InternalError", "Request could not be executed due to an internal service error.", 500);
        }
    }

    private static Response ok(String action, String result) {
        XmlBuilder xml = new XmlBuilder().start(action + "Response", NS);
        if (result != null) {
            xml.start(action + "Result").raw(result).end(action + "Result");
        }
        xml.start("ResponseMetadata").elem("RequestId", UUID.randomUUID().toString())
                .elem("BoxUsage", BOX_USAGE).end("ResponseMetadata").end(action + "Response");
        return Response.ok(xml.build(), MediaType.TEXT_XML).build();
    }

    static Response error(String code, String message, int status) {
        String xml = new XmlBuilder().start("Response").start("Errors").start("Error").elem("Code", code)
                .elem("Message", message).elem("BoxUsage", BOX_USAGE).end("Error").end("Errors")
                .elem("RequestID", UUID.randomUUID().toString()).end("Response").build();
        return Response.status(status).entity(xml).type(MediaType.TEXT_XML).build();
    }

    // ---------- parameters ----------

    /** {@code Attribute.N.Name/Value/Replace}, or names alone for a delete. */
    private static List<SimpleDbService.Change> changes(MultivaluedMap<String, String> p, String prefix,
                                                        boolean put) {
        List<SimpleDbService.Change> out = new ArrayList<>();
        for (int i = 1; p.containsKey(prefix + "." + i + ".Name") || p.containsKey(prefix + "." + i + ".Value");
             i++) {
            String name = p.getFirst(prefix + "." + i + ".Name");
            String value = p.getFirst(prefix + "." + i + ".Value");
            out.add(new SimpleDbService.Change(name, value,
                    put && "true".equalsIgnoreCase(p.getFirst(prefix + "." + i + ".Replace"))));
        }
        return out;
    }

    private static List<SimpleDbService.Expected> expected(MultivaluedMap<String, String> p) {
        List<SimpleDbService.Expected> out = new ArrayList<>();
        for (int i = 1; p.containsKey("Expected." + i + ".Name"); i++) {
            String exists = p.getFirst("Expected." + i + ".Exists");
            out.add(new SimpleDbService.Expected(p.getFirst("Expected." + i + ".Name"),
                    p.getFirst("Expected." + i + ".Value"), exists == null ? null : Boolean.valueOf(exists)));
        }
        if (out.isEmpty() && p.containsKey("Expected.Name")) {
            String exists = p.getFirst("Expected.Exists");
            out.add(new SimpleDbService.Expected(p.getFirst("Expected.Name"), p.getFirst("Expected.Value"),
                    exists == null ? null : Boolean.valueOf(exists)));
        }
        return out;
    }

    /** {@code Item.N.ItemName} with {@code Item.N.Attribute.M.…}; an item name twice is an error. */
    private static Map<String, List<SimpleDbService.Change>> batch(MultivaluedMap<String, String> p, boolean put) {
        Map<String, List<SimpleDbService.Change>> out = new LinkedHashMap<>();
        for (int i = 1; p.containsKey("Item." + i + ".ItemName"); i++) {
            String item = p.getFirst("Item." + i + ".ItemName");
            if (out.containsKey(item)) {
                throw new AwsException("DuplicateItemName", "Item " + item + " was specified more than once.", 400);
            }
            out.put(item, changes(p, "Item." + i + ".Attribute", put));
        }
        if (out.isEmpty()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter Item.1.ItemName.", 400);
        }
        return out;
    }

    // ---------- reads ----------

    private Response listDomains(MultivaluedMap<String, String> p, String region) {
        int max = parseInt(p.getFirst("MaxNumberOfDomains"), 100, "MaxNumberOfDomains");
        if (max < 1 || max > 100) {
            throw new AwsException("InvalidParameterValue", "Value (" + max + ") for parameter MaxNumberOfDomains is "
                    + "invalid. MaxNumberOfDomains must be between 1 and 100.", 400);
        }
        List<String> names = service.listDomains(region);
        int start = token(p.getFirst("NextToken"));
        int end = Math.min(names.size(), start + max);
        XmlBuilder xml = new XmlBuilder();
        for (String name : names.subList(Math.min(start, names.size()), end)) {
            xml.elem("DomainName", name);
        }
        if (end < names.size()) {
            xml.elem("NextToken", nextToken(end));
        }
        return ok("ListDomains", xml.build());
    }

    private Response domainMetadata(MultivaluedMap<String, String> p, String region) {
        XmlBuilder xml = new XmlBuilder();
        service.metadata(region, p.getFirst("DomainName")).forEach(xml::elem);
        return ok("DomainMetadata", xml.build());
    }

    private Response getAttributes(MultivaluedMap<String, String> p, String region) {
        List<String> names = new ArrayList<>();
        for (int i = 1; p.containsKey("AttributeName." + i); i++) {
            names.add(p.getFirst("AttributeName." + i));
        }
        XmlBuilder xml = new XmlBuilder();
        attributes(xml, service.getAttributes(region, p.getFirst("DomainName"), p.getFirst("ItemName"), names));
        return ok("GetAttributes", xml.build());
    }

    private Response select(MultivaluedMap<String, String> p, String region) {
        SimpleDbService.SelectResult result = service.select(region, p.getFirst("SelectExpression"),
                token(p.getFirst("NextToken")));
        XmlBuilder xml = new XmlBuilder();
        for (SimpleDbSelect.Item item : result.items()) {
            xml.start("Item").elem("Name", item.name());
            attributes(xml, item.attributes());
            xml.end("Item");
        }
        if (result.next() >= 0) {
            xml.elem("NextToken", nextToken(result.next()));
        }
        return ok("Select", xml.build());
    }

    private static void attributes(XmlBuilder xml, Map<String, Set<String>> attributes) {
        attributes.forEach((name, values) -> {
            for (String value : values) {
                xml.start("Attribute").elem("Name", name).elem("Value", value).end("Attribute");
            }
        });
    }

    private static int parseInt(String value, int fallback, String name) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", "Value (" + value + ") for parameter " + name
                    + " is invalid.", 400);
        }
    }

    private static String nextToken(int offset) {
        return Base64.getEncoder().encodeToString(("sdb:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int token(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith("sdb:")) {
                throw new IllegalArgumentException(decoded);
            }
            return Integer.parseInt(decoded.substring(4));
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidNextToken", "The specified NextToken is not valid.", 400);
        }
    }
}
