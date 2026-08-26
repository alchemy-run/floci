package io.github.hectorvent.floci.services.simpledb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SimpleDbQueryHandler {

    private static final Logger LOG = Logger.getLogger(SimpleDbQueryHandler.class);
    private static final String NS = AwsNamespaces.SDB;

    private final SimpleDbService service;

    @Inject
    SimpleDbQueryHandler(SimpleDbService service) {
        this.service = service;
    }

    public Response handle(String action, MultivaluedMap<String, String> p, String region) {
        LOG.debugv("SimpleDB action: {0}", action);
        try {
            return switch (action) {
                case "CreateDomain" -> createDomain(p, region);
                case "DeleteDomain" -> deleteDomain(p, region);
                case "ListDomains" -> listDomains(p, region);
                case "DomainMetadata" -> domainMetadata(p, region);
                case "PutAttributes" -> putAttributes(p, region);
                case "GetAttributes" -> getAttributes(p, region);
                case "DeleteAttributes" -> deleteAttributes(p, region);
                case "BatchPutAttributes" -> batchPutAttributes(p, region);
                case "BatchDeleteAttributes" -> batchDeleteAttributes(p, region);
                case "Select" -> select(p, region);
                default -> AwsQueryResponse.error("InvalidAction",
                        "The action " + action + " is not valid for this web service.", NS, 400);
            };
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), NS, e.getHttpStatus());
        } catch (Exception e) {
            LOG.errorv(e, "Unexpected error in SimpleDB {0}", action);
            return AwsQueryResponse.error("InternalFailure", e.getMessage(), NS, 500);
        }
    }

    private Response createDomain(MultivaluedMap<String, String> p, String region) {
        service.createDomain(region, p.getFirst("DomainName"));
        return ok(AwsQueryResponse.envelopeNoResult("CreateDomain", NS));
    }

    private Response deleteDomain(MultivaluedMap<String, String> p, String region) {
        service.deleteDomain(region, p.getFirst("DomainName"));
        return ok(AwsQueryResponse.envelopeNoResult("DeleteDomain", NS));
    }

    private Response listDomains(MultivaluedMap<String, String> p, String region) {
        Integer max = parseInteger(p.getFirst("MaxNumberOfDomains"));
        List<String> names = service.listDomains(region, max, p.getFirst("NextToken"));
        XmlBuilder xml = new XmlBuilder();
        for (String name : names) {
            xml.elem("DomainName", name);
        }
        return ok(AwsQueryResponse.envelope("ListDomains", NS, xml.build()));
    }

    private Response domainMetadata(MultivaluedMap<String, String> p, String region) {
        SimpleDbService.DomainMetadata metadata = service.domainMetadata(region, p.getFirst("DomainName"));
        String result = new XmlBuilder()
                .elem("ItemCount", metadata.itemCount())
                .elem("ItemNamesSizeBytes", metadata.itemNamesSizeBytes())
                .elem("AttributeNameCount", metadata.attributeNameCount())
                .elem("AttributeNamesSizeBytes", metadata.attributeNamesSizeBytes())
                .elem("AttributeValueCount", metadata.attributeValueCount())
                .elem("AttributeValuesSizeBytes", metadata.attributeValuesSizeBytes())
                .elem("Timestamp", metadata.timestamp())
                .build();
        return ok(AwsQueryResponse.envelope("DomainMetadata", NS, result));
    }

    private Response putAttributes(MultivaluedMap<String, String> p, String region) {
        service.putAttributes(region, p.getFirst("DomainName"), p.getFirst("ItemName"),
                parseAttributes(p, "Attribute"));
        return ok(AwsQueryResponse.envelopeNoResult("PutAttributes", NS));
    }

    private Response getAttributes(MultivaluedMap<String, String> p, String region) {
        List<SimpleDbService.AttributePair> attributes = service.getAttributes(
                region, p.getFirst("DomainName"), p.getFirst("ItemName"), parseAttributeNames(p));
        XmlBuilder xml = new XmlBuilder();
        appendAttributes(xml, attributes);
        return ok(AwsQueryResponse.envelope("GetAttributes", NS, xml.build()));
    }

    private Response deleteAttributes(MultivaluedMap<String, String> p, String region) {
        service.deleteAttributes(region, p.getFirst("DomainName"), p.getFirst("ItemName"),
                parseAttributes(p, "Attribute"));
        return ok(AwsQueryResponse.envelopeNoResult("DeleteAttributes", NS));
    }

    private Response batchPutAttributes(MultivaluedMap<String, String> p, String region) {
        service.batchPutAttributes(region, p.getFirst("DomainName"), parseItems(p));
        return ok(AwsQueryResponse.envelopeNoResult("BatchPutAttributes", NS));
    }

    private Response batchDeleteAttributes(MultivaluedMap<String, String> p, String region) {
        service.batchDeleteAttributes(region, p.getFirst("DomainName"), parseItems(p));
        return ok(AwsQueryResponse.envelopeNoResult("BatchDeleteAttributes", NS));
    }

    private Response select(MultivaluedMap<String, String> p, String region) {
        SimpleDbService.SelectResult result = service.select(region, p.getFirst("SelectExpression"));
        XmlBuilder xml = new XmlBuilder();
        for (SimpleDbService.SelectedItem item : result.items()) {
            xml.start("Item").elem("Name", item.name());
            appendAttributes(xml, item.attributes());
            xml.end("Item");
        }
        return ok(AwsQueryResponse.envelope("Select", NS, xml.build()));
    }

    private static void appendAttributes(XmlBuilder xml, List<SimpleDbService.AttributePair> attributes) {
        for (SimpleDbService.AttributePair attribute : attributes) {
            xml.start("Attribute")
                    .elem("Name", attribute.name())
                    .elem("Value", attribute.value())
                    .end("Attribute");
        }
    }

    private static List<SimpleDbService.AttributeUpdate> parseAttributes(
            MultivaluedMap<String, String> p, String prefix) {
        List<SimpleDbService.AttributeUpdate> attributes = new ArrayList<>();
        for (int i = 1; ; i++) {
            String name = indexed(p, prefix, i, "Name");
            if (name == null) {
                break;
            }
            String value = indexed(p, prefix, i, "Value");
            String replace = indexed(p, prefix, i, "Replace");
            attributes.add(new SimpleDbService.AttributeUpdate(name, value, Boolean.parseBoolean(replace)));
        }
        return attributes;
    }

    private static List<SimpleDbService.ItemUpdate> parseItems(MultivaluedMap<String, String> p) {
        List<SimpleDbService.ItemUpdate> items = new ArrayList<>();
        for (int i = 1; ; i++) {
            String itemName = indexed(p, "Item", i, "ItemName");
            if (itemName == null) {
                break;
            }
            items.add(new SimpleDbService.ItemUpdate(itemName, parseAttributes(p, "Item." + i + ".Attribute")));
        }
        return items;
    }

    private static List<String> parseAttributeNames(MultivaluedMap<String, String> p) {
        List<String> names = new ArrayList<>();
        for (int i = 1; ; i++) {
            String name = first(p, "AttributeName." + i, "AttributeName.member." + i);
            if (name == null) {
                break;
            }
            names.add(name);
        }
        return names;
    }

    private static String indexed(MultivaluedMap<String, String> p, String prefix, int index, String field) {
        return first(p, prefix + "." + index + "." + field, prefix + ".member." + index + "." + field);
    }

    private static String first(MultivaluedMap<String, String> p, String... keys) {
        for (String key : keys) {
            String value = p.getFirst(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue",
                    "Value (" + value + ") for parameter MaxNumberOfDomains is invalid. Reason: Must be an integer.",
                    400);
        }
    }

    private static Response ok(String xml) {
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }
}
