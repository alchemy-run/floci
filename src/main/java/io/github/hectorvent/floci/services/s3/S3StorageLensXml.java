package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.s3.model.S3StorageLensConfiguration;
import org.jboss.logging.Logger;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST-XML (de)serialization for S3 Storage Lens configurations.
 */
final class S3StorageLensXml {

    private static final Logger LOG = Logger.getLogger(S3StorageLensXml.class);
    private static final XMLInputFactory FACTORY;

    static {
        FACTORY = XMLInputFactory.newInstance();
        FACTORY.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    private S3StorageLensXml() {}

    static void applyPutBody(S3StorageLensConfiguration config, String xml, String pathConfigId) {
        Parsed parsed = parse(xml);
        String id = parsed.id != null && !parsed.id.isBlank() ? parsed.id.trim() : pathConfigId;
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidRequest", "Storage Lens configuration Id is required.", 400);
        }
        if (pathConfigId != null && !pathConfigId.isBlank() && !id.equals(pathConfigId)) {
            throw new AwsException("InvalidRequest",
                    "Storage Lens configuration Id must match the ConfigId in the request URI.", 400);
        }
        if (parsed.accountLevelInner == null) {
            throw new AwsException("InvalidRequest", "AccountLevel is required.", 400);
        }
        validateActivityMetrics(parsed.accountLevelInner);
        config.setConfigId(id);
        config.setEnabled(parsed.enabled);
        config.setAccountLevelXml(parsed.accountLevelInner);
        config.setIncludeXml(parsed.includeInner);
        config.setExcludeXml(parsed.excludeInner);
        config.setDataExportXml(parsed.dataExportInner);
        config.setExpandedPrefixesXml(parsed.expandedInner);
        config.setAwsOrgXml(parsed.awsOrgInner);
        config.setPrefixDelimiter(parsed.prefixDelimiter);
        if (!parsed.tags.isEmpty()) {
            config.setTags(new LinkedHashMap<>(parsed.tags));
        }
    }

    static Map<String, String> tagsFrom(String xml) {
        return XmlParser.extractPairs(xml == null ? "" : xml, "Tag", "Key", "Value");
    }

    static String toGetXml(S3StorageLensConfiguration config) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("StorageLensConfiguration", AwsNamespaces.S3_CONTROL)
                .elem("Id", config.getConfigId());
        appendOptionalElement(xml, "AccountLevel", config.getAccountLevelXml());
        appendOptionalElement(xml, "Include", config.getIncludeXml());
        appendOptionalElement(xml, "Exclude", config.getExcludeXml());
        appendOptionalElement(xml, "DataExport", config.getDataExportXml());
        appendOptionalElement(xml, "ExpandedPrefixesDataExport", config.getExpandedPrefixesXml());
        xml.elem("IsEnabled", config.isEnabled());
        appendOptionalElement(xml, "AwsOrg", config.getAwsOrgXml());
        xml.elem("StorageLensArn", config.getArn());
        xml.elem("PrefixDelimiter", config.getPrefixDelimiter());
        xml.end("StorageLensConfiguration");
        return xml.build();
    }

    static String toListEntryXml(XmlBuilder xml, S3StorageLensConfiguration config) {
        xml.start("StorageLensConfiguration")
                .elem("Id", config.getConfigId())
                .elem("StorageLensArn", config.getArn())
                .elem("HomeRegion", config.getRegion())
                .elem("IsEnabled", config.isEnabled())
                .end("StorageLensConfiguration");
        return xml.build();
    }

    static String toTagsXml(Map<String, String> tags) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("GetStorageLensConfigurationTaggingResult", AwsNamespaces.S3_CONTROL)
                .start("Tags");
        if (tags != null) {
            tags.forEach((k, v) -> xml.start("Tag").elem("Key", k).elem("Value", v).end("Tag"));
        }
        xml.end("Tags").end("GetStorageLensConfigurationTaggingResult");
        return xml.build();
    }

    private static void appendOptionalElement(XmlBuilder xml, String name, String inner) {
        if (inner == null) {
            return;
        }
        xml.raw("<" + name + ">" + inner + "</" + name + ">");
    }

    private static void validateActivityMetrics(String accountLevelInner) {
        boolean accountActivity = isMetricsEnabled(accountLevelInner, "ActivityMetrics");
        if (!accountActivity) {
            return;
        }
        String bucketInner = copyDirectChildInner(wrap(accountLevelInner), "BucketLevel");
        boolean bucketActivity = isMetricsEnabled(bucketInner, "ActivityMetrics");
        if (!bucketActivity) {
            throw new AwsException("MissingBucketLevelActivityMetrics",
                    "Activity metrics must be enabled at the bucket level when they are enabled at the account level.",
                    400);
        }
    }

    private static boolean isMetricsEnabled(String parentInner, String metricsName) {
        if (parentInner == null || parentInner.isBlank()) {
            return false;
        }
        String metricsInner = copyDirectChildInner(wrap(parentInner), metricsName);
        if (metricsInner == null) {
            return false;
        }
        String enabled = copyDirectChildText(wrap(metricsInner), "IsEnabled");
        return Boolean.parseBoolean(enabled);
    }

    private static String wrap(String inner) {
        return "<x>" + (inner == null ? "" : inner) + "</x>";
    }

    private static Parsed parse(String xml) {
        Parsed parsed = new Parsed();
        parsed.tags.putAll(tagsFrom(xml));
        if (xml == null || xml.isBlank()) {
            throw new AwsException("InvalidRequest", "StorageLensConfiguration is required.", 400);
        }
        try {
            XMLStreamReader r = FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean found = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && "StorageLensConfiguration".equals(r.getLocalName())) {
                    parseConfig(r, parsed);
                    found = true;
                    break;
                }
            }
            r.close();
            if (!found) {
                throw new AwsException("InvalidRequest", "StorageLensConfiguration is required.", 400);
            }
            return parsed;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.debugv("Ignoring malformed Storage Lens XML during parse: {0}", e.getMessage());
            throw new AwsException("InvalidRequest", "Malformed Storage Lens configuration XML.", 400);
        }
    }

    private static void parseConfig(XMLStreamReader r, Parsed parsed) throws Exception {
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (depth == 1) {
                    String local = r.getLocalName();
                    switch (local) {
                        case "Id" -> parsed.id = r.getElementText();
                        case "IsEnabled" -> parsed.enabled = Boolean.parseBoolean(r.getElementText());
                        case "PrefixDelimiter" -> parsed.prefixDelimiter = r.getElementText();
                        case "AccountLevel" -> parsed.accountLevelInner = copyInner(r);
                        case "Include" -> parsed.includeInner = copyInner(r);
                        case "Exclude" -> parsed.excludeInner = copyInner(r);
                        case "DataExport" -> parsed.dataExportInner = copyInner(r);
                        case "ExpandedPrefixesDataExport" -> parsed.expandedInner = copyInner(r);
                        case "AwsOrg" -> parsed.awsOrgInner = copyInner(r);
                        default -> copyInner(r);
                    }
                    continue;
                }
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    private static String copyDirectChildInner(String xml, String childName) {
        if (xml == null || xml.isBlank() || childName == null) {
            return null;
        }
        try {
            XMLStreamReader r = FACTORY.createXMLStreamReader(new StringReader(xml));
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT) {
                    break;
                }
            }
            int depth = 1;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (depth == 1 && childName.equals(r.getLocalName())) {
                        String inner = copyInner(r);
                        r.close();
                        return inner;
                    }
                    depth++;
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            r.close();
        } catch (Exception e) {
            LOG.debugv("Ignoring malformed Storage Lens XML during child parse: {0}", e.getMessage());
        }
        return null;
    }

    private static String copyDirectChildText(String xml, String childName) {
        String inner = copyDirectChildInner(xml, childName);
        if (inner == null) {
            return null;
        }
        return inner.isBlank() ? "" : inner;
    }

    /**
     * Copies the inner XML of the element the reader is currently positioned on
     * (START_ELEMENT) and leaves the reader on that element's END_ELEMENT.
     */
    private static String copyInner(XMLStreamReader r) throws Exception {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                sb.append('<').append(r.getLocalName()).append('>');
                depth++;
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                sb.append(XmlBuilder.escape(r.getText()));
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
                if (depth > 0) {
                    sb.append("</").append(r.getLocalName()).append('>');
                }
            }
        }
        return sb.toString();
    }

    private static final class Parsed {
        private String id;
        private boolean enabled = true;
        private String accountLevelInner;
        private String includeInner;
        private String excludeInner;
        private String dataExportInner;
        private String expandedInner;
        private String awsOrgInner;
        private String prefixDelimiter;
        private final Map<String, String> tags = new LinkedHashMap<>();
    }
}
