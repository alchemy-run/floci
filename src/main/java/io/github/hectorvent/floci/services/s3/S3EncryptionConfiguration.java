package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

record S3EncryptionConfiguration(String algorithm, String kmsKeyId, boolean bucketKeyEnabled,
                                 String blockedEncryptionType) {

    private static final String ROOT = "ServerSideEncryptionConfiguration";
    static final S3EncryptionConfiguration DEFAULT = new S3EncryptionConfiguration("AES256", null, false, "NONE");

    static S3EncryptionConfiguration parse(String xml) {
        Element root;
        try {
            if (xml == null || xml.isBlank()) {
                throw malformed();
            }
            root = XmlParser.parseDocument(xml).getDocumentElement();
        } catch (Exception e) {
            throw malformed();
        }
        if (!ROOT.equals(root.getLocalName())) {
            throw malformed();
        }
        Map<String, Element> configuration = children(root, Set.of("Rule"));
        Element rule = required(configuration, "Rule");
        Map<String, Element> settings = children(rule, Set.of("ApplyServerSideEncryptionByDefault",
                "BucketKeyEnabled", "BlockedEncryptionTypes"));
        if (settings.isEmpty()) {
            throw malformed();
        }

        String algorithm = "AES256";
        String kmsKeyId = null;
        Element defaults = settings.get("ApplyServerSideEncryptionByDefault");
        if (defaults != null) {
            Map<String, Element> encryption = children(defaults, Set.of("SSEAlgorithm", "KMSMasterKeyID"));
            algorithm = text(required(encryption, "SSEAlgorithm"));
            if (!Set.of("AES256", "aws:kms", "aws:kms:dsse").contains(algorithm)) {
                throw invalid("The SSEAlgorithm must be AES256, aws:kms, or aws:kms:dsse.");
            }
            if (encryption.containsKey("KMSMasterKeyID")) {
                kmsKeyId = text(encryption.get("KMSMasterKeyID"));
                if (kmsKeyId.isEmpty() || "AES256".equals(algorithm)) {
                    throw invalid("KMSMasterKeyID requires a KMS encryption algorithm and a nonempty key ID.");
                }
            }
        }

        boolean bucketKeyEnabled = false;
        if (settings.containsKey("BucketKeyEnabled")) {
            bucketKeyEnabled = switch (text(settings.get("BucketKeyEnabled"))) {
                case "true", "1" -> true;
                case "false", "0" -> false;
                default -> throw malformed();
            };
        }
        String blocked = "NONE";
        if (settings.containsKey("BlockedEncryptionTypes")) {
            Map<String, Element> types = children(settings.get("BlockedEncryptionTypes"), Set.of("EncryptionType"));
            blocked = text(required(types, "EncryptionType"));
            if (!Set.of("NONE", "SSE-C").contains(blocked)) {
                throw invalid("BlockedEncryptionTypes must contain NONE or SSE-C.");
            }
        }
        return new S3EncryptionConfiguration(algorithm, kmsKeyId, bucketKeyEnabled, blocked);
    }

    static S3EncryptionConfiguration fromStored(String xml) {
        return xml == null ? DEFAULT : parse(xml);
    }

    String toXml() {
        return new XmlBuilder()
                .start(ROOT, AwsNamespaces.S3)
                .start("Rule")
                .start("ApplyServerSideEncryptionByDefault")
                .elem("SSEAlgorithm", algorithm)
                .elem("KMSMasterKeyID", kmsKeyId)
                .end("ApplyServerSideEncryptionByDefault")
                .elem("BucketKeyEnabled", bucketKeyEnabled)
                .start("BlockedEncryptionTypes")
                .elem("EncryptionType", blockedEncryptionType)
                .end("BlockedEncryptionTypes")
                .end("Rule")
                .end(ROOT)
                .build();
    }

    private static Map<String, Element> children(Element parent, Set<String> allowed) {
        Map<String, Element> children = new LinkedHashMap<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                String name = child.getLocalName();
                if (!allowed.contains(name) || children.putIfAbsent(name, child) != null) {
                    throw malformed();
                }
            } else if ((node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !node.getTextContent().isBlank()) {
                throw malformed();
            }
        }
        return children;
    }

    private static Element required(Map<String, Element> children, String name) {
        Element element = children.get(name);
        if (element == null) {
            throw malformed();
        }
        return element;
    }

    private static String text(Element element) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element) {
                throw malformed();
            }
        }
        return element.getTextContent().trim();
    }

    private static AwsException malformed() {
        return new AwsException("MalformedXML",
                "The XML you provided was not well-formed or did not validate against our published schema", 400);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidArgument", message, 400);
    }
}
