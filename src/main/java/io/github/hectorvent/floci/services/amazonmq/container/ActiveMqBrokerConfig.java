package io.github.hectorvent.floci.services.amazonmq.container;

import org.jboss.logging.Logger;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Renders the files an ActiveMQ Classic broker container boots from: {@code conf/activemq.xml}
 * and the web console realm {@code conf/jetty-realm.properties}.
 *
 * <p>The broker XML follows how Amazon MQ treats a configuration revision: the user's
 * {@code <broker>} document contributes only the elements and attributes Amazon MQ permits
 * (destination policies, destinations, interceptors, network connectors, and non-authentication
 * plugins such as {@code authorizationPlugin}), while transports, persistence, system usage and
 * authentication stay service-managed. Broker users become a {@code simpleAuthenticationPlugin},
 * so clients authenticate with the credentials created through CreateBroker and the User API.
 * Broker children are emitted in alphabetical order, the order Amazon MQ requires.
 */
public final class ActiveMqBrokerConfig {

    private static final Logger LOG = Logger.getLogger(ActiveMqBrokerConfig.class);

    static final String CORE_NS = "http://activemq.apache.org/schema/core";
    private static final String BEANS_NS = "http://www.springframework.org/schema/beans";
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String SCHEMA_LOCATION = BEANS_NS + " " + BEANS_NS + "/spring-beans.xsd "
            + CORE_NS + " " + CORE_NS + "/activemq-core.xsd";
    private static final String CONNECTOR_OPTIONS = "?maximumConnections=1000&wireFormat.maxFrameSize=104857600";

    /** Broker children a configuration revision may contribute; everything else is service-managed. */
    private static final Set<String> USER_BROKER_CHILDREN = Set.of(
            "destinationInterceptors", "destinationPolicy", "destinations", "networkConnectors");

    /** Broker attributes Amazon MQ accepts from a configuration revision. */
    private static final Set<String> USER_BROKER_ATTRIBUTES = Set.of(
            "consumerSystemUsagePortion", "dedicatedTaskRunner", "deleteAllMessagesOnStartup",
            "keepDurableSubsActive", "monitorConnectionSplits", "networkConnectorStartAsync",
            "offlineDurableSubscriberTaskSchedule", "offlineDurableSubscriberTimeout",
            "persistenceThreadPriority", "persistent", "populateJMSXUserID", "producerSystemUsagePortion",
            "rejectDurableConsumers", "rollbackOnlyOnAsyncException", "schedulePeriodForDestinationPurge",
            "schedulerSupport", "splitSystemUsageForProducersConsumers", "taskRunnerPriority",
            "timeBeforePurgeTempDestinations", "useAuthenticatedPrincipalForJMSXUserID",
            "useMirroredQueues", "useTempMirroredQueues", "useVirtualDestSubs",
            "useVirtualDestSubsOnCreation", "useVirtualTopics");

    /** Authentication is service-managed, so these plugins are never taken from a revision. */
    private static final Set<String> MANAGED_PLUGINS = Set.of(
            "simpleAuthenticationPlugin", "jaasAuthenticationPlugin", "jaasCertificateAuthenticationPlugin",
            "jaasDualAuthenticationPlugin", "cachedLDAPAuthorizationMap");

    /** One broker user as it is materialized inside the broker. */
    public record Credential(String username, String password, boolean consoleAccess, List<String> groups) {}

    private ActiveMqBrokerConfig() {}

    /**
     * Renders {@code activemq.xml}.
     *
     * @param credentials the broker's active users; every one must carry its password
     * @param configurationDocument the decoded {@code <broker>} document of the broker's
     *        configuration revision, or {@code null} when the broker has none
     */
    public static String activemqXml(List<Credential> credentials, String configurationDocument) {
        try {
            DocumentBuilder builder = documentBuilder();
            Document doc = builder.newDocument();
            Element beans = doc.createElementNS(BEANS_NS, "beans");
            beans.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", BEANS_NS);
            beans.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsi", XSI_NS);
            beans.setAttributeNS(XSI_NS, "xsi:schemaLocation", SCHEMA_LOCATION);
            doc.appendChild(beans);

            // System properties such as ${activemq.data} resolve through the placeholder
            // configurer. Unresolvable placeholders stay literal so a password containing
            // "${" cannot stop the broker from booting.
            Element placeholder = beansChild(doc, beans, "bean");
            placeholder.setAttribute("class",
                    "org.springframework.beans.factory.config.PropertyPlaceholderConfigurer");
            Element ignore = beansChild(doc, placeholder, "property");
            ignore.setAttribute("name", "ignoreUnresolvablePlaceholders");
            ignore.setAttribute("value", "true");

            Element broker = doc.createElementNS(CORE_NS, "broker");
            broker.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", CORE_NS);
            broker.setAttribute("brokerName", "localhost");
            broker.setAttribute("dataDirectory", "${activemq.data}");
            beans.appendChild(broker);

            Element userBroker = parseUserBroker(builder, configurationDocument);
            TreeMap<String, List<Element>> children = new TreeMap<>();
            List<Element> userPlugins = new ArrayList<>();
            if (userBroker != null) {
                copyPermittedAttributes(userBroker, broker);
                for (Element child : childElements(userBroker)) {
                    String name = child.getLocalName();
                    if (!CORE_NS.equals(child.getNamespaceURI())) {
                        continue;
                    }
                    if (USER_BROKER_CHILDREN.contains(name)) {
                        children.computeIfAbsent(name, k -> new ArrayList<>())
                                .add((Element) doc.importNode(child, true));
                    } else if ("plugins".equals(name)) {
                        for (Element plugin : childElements(child)) {
                            if (CORE_NS.equals(plugin.getNamespaceURI())
                                    && !MANAGED_PLUGINS.contains(plugin.getLocalName())) {
                                userPlugins.add((Element) doc.importNode(plugin, true));
                            }
                        }
                    }
                }
            }
            if (!children.containsKey("destinationPolicy")) {
                children.put("destinationPolicy", List.of(defaultDestinationPolicy(doc)));
            }
            children.put("managementContext", List.of(managementContext(doc)));
            children.put("persistenceAdapter", List.of(persistenceAdapter(doc)));
            children.put("plugins", List.of(plugins(doc, userPlugins, credentials)));
            children.put("shutdownHooks", List.of(shutdownHooks(doc)));
            children.put("systemUsage", List.of(systemUsage(doc)));
            children.put("transportConnectors", List.of(transportConnectors(doc)));
            for (Map.Entry<String, List<Element>> entry : children.entrySet()) {
                for (Element element : entry.getValue()) {
                    broker.appendChild(element);
                }
            }

            Element jetty = beansChild(doc, beans, "import");
            jetty.setAttribute("resource", "jetty.xml");
            return serialize(doc);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Could not render the ActiveMQ broker configuration", e);
        }
    }

    /**
     * Renders the web console realm. Only users with console access may sign in, and they
     * get the {@code admin} role the console's constraints require.
     */
    public static String jettyRealm(List<Credential> credentials) {
        StringBuilder realm = new StringBuilder();
        for (Credential credential : credentials) {
            if (credential.consoleAccess()) {
                realm.append(credential.username()).append(": ")
                        .append(escapeProperty(credential.password())).append(", admin\n");
            }
        }
        return realm.toString();
    }

    private static String escapeProperty(String value) {
        return value.replace("\\", "\\\\");
    }

    private static Element parseUserBroker(DocumentBuilder builder, String configurationDocument) {
        if (configurationDocument == null || configurationDocument.isBlank()) {
            return null;
        }
        try {
            Element root = builder.parse(new InputSource(new StringReader(configurationDocument)))
                    .getDocumentElement();
            return "broker".equals(root.getLocalName()) ? root : null;
        } catch (Exception e) {
            LOG.warnv("Ignoring an unparseable ActiveMQ configuration revision: {0}", e.getMessage());
            return null;
        }
    }

    private static void copyPermittedAttributes(Element from, Element to) {
        NamedNodeMap attributes = from.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            String name = attribute.getLocalName() != null ? attribute.getLocalName() : attribute.getName();
            if (attribute.getNamespaceURI() == null && USER_BROKER_ATTRIBUTES.contains(name)) {
                to.setAttribute(name, attribute.getValue());
            }
        }
    }

    private static List<Element> childElements(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static Element plugins(Document doc, List<Element> userPlugins, List<Credential> credentials) {
        Element plugins = doc.createElementNS(CORE_NS, "plugins");
        TreeMap<String, List<Element>> ordered = new TreeMap<>();
        for (Element plugin : userPlugins) {
            ordered.computeIfAbsent(plugin.getLocalName(), k -> new ArrayList<>()).add(plugin);
        }
        Element authentication = doc.createElementNS(CORE_NS, "simpleAuthenticationPlugin");
        authentication.setAttribute("anonymousAccessAllowed", "false");
        Element users = coreChild(doc, authentication, "users");
        for (Credential credential : credentials) {
            if (credential.password() == null) {
                throw new IllegalStateException("Password unavailable for broker user " + credential.username());
            }
            Element user = coreChild(doc, users, "authenticationUser");
            user.setAttribute("username", credential.username());
            user.setAttribute("password", credential.password());
            user.setAttribute("groups", credential.groups() == null ? "" : String.join(",", credential.groups()));
        }
        ordered.computeIfAbsent("simpleAuthenticationPlugin", k -> new ArrayList<>()).add(authentication);
        for (List<Element> elements : ordered.values()) {
            for (Element element : elements) {
                plugins.appendChild(element);
            }
        }
        return plugins;
    }

    private static Element defaultDestinationPolicy(Document doc) {
        Element destinationPolicy = doc.createElementNS(CORE_NS, "destinationPolicy");
        Element entries = coreChild(doc, coreChild(doc, destinationPolicy, "policyMap"), "policyEntries");
        Element topics = coreChild(doc, entries, "policyEntry");
        topics.setAttribute("topic", ">");
        Element limit = coreChild(doc, coreChild(doc, topics, "pendingMessageLimitStrategy"),
                "constantPendingMessageLimitStrategy");
        limit.setAttribute("limit", "1000");
        return destinationPolicy;
    }

    private static Element managementContext(Document doc) {
        Element managementContext = doc.createElementNS(CORE_NS, "managementContext");
        coreChild(doc, managementContext, "managementContext").setAttribute("createConnector", "false");
        return managementContext;
    }

    private static Element persistenceAdapter(Document doc) {
        Element persistenceAdapter = doc.createElementNS(CORE_NS, "persistenceAdapter");
        coreChild(doc, persistenceAdapter, "kahaDB").setAttribute("directory", "${activemq.data}/kahadb");
        return persistenceAdapter;
    }

    private static Element shutdownHooks(Document doc) {
        Element shutdownHooks = doc.createElementNS(CORE_NS, "shutdownHooks");
        Element hook = doc.createElementNS(BEANS_NS, "bean");
        hook.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", BEANS_NS);
        hook.setAttribute("class", "org.apache.activemq.hooks.SpringContextHook");
        shutdownHooks.appendChild(hook);
        return shutdownHooks;
    }

    private static Element systemUsage(Document doc) {
        Element outer = doc.createElementNS(CORE_NS, "systemUsage");
        Element usage = coreChild(doc, outer, "systemUsage");
        coreChild(doc, coreChild(doc, usage, "memoryUsage"), "memoryUsage").setAttribute("percentOfJvmHeap", "70");
        coreChild(doc, coreChild(doc, usage, "storeUsage"), "storeUsage").setAttribute("limit", "20 gb");
        coreChild(doc, coreChild(doc, usage, "tempUsage"), "tempUsage").setAttribute("limit", "5 gb");
        return outer;
    }

    private static Element transportConnectors(Document doc) {
        Element connectors = doc.createElementNS(CORE_NS, "transportConnectors");
        addConnector(doc, connectors, "openwire", "tcp", ActiveMqManager.OPENWIRE_PORT);
        addConnector(doc, connectors, "amqp", "amqp", ActiveMqManager.AMQP_PORT);
        addConnector(doc, connectors, "stomp", "stomp", ActiveMqManager.STOMP_PORT);
        addConnector(doc, connectors, "mqtt", "mqtt", ActiveMqManager.MQTT_PORT);
        addConnector(doc, connectors, "ws", "ws", ActiveMqManager.WS_PORT);
        return connectors;
    }

    private static void addConnector(Document doc, Element connectors, String name, String scheme, int port) {
        Element connector = coreChild(doc, connectors, "transportConnector");
        connector.setAttribute("name", name);
        connector.setAttribute("uri", scheme + "://0.0.0.0:" + port + CONNECTOR_OPTIONS);
    }

    private static Element coreChild(Document doc, Element parent, String name) {
        Element child = doc.createElementNS(CORE_NS, name);
        parent.appendChild(child);
        return child;
    }

    private static Element beansChild(Document doc, Element parent, String name) {
        Element child = doc.createElementNS(BEANS_NS, name);
        parent.appendChild(child);
        return child;
    }

    private static DocumentBuilder documentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(SAXParseException e) throws SAXParseException {
                throw e;
            }
        });
        return builder;
    }

    /**
     * Writes the document as indented XML, declaring every namespace an element or attribute
     * uses that is not already in scope (imported revision nodes lose their root's declarations).
     */
    private static String serialize(Document doc) {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        writeElement(out, doc.getDocumentElement(), new HashMap<>(), 0);
        return out.toString();
    }

    private static void writeElement(StringBuilder out, Element element, Map<String, String> inScope, int depth) {
        Map<String, String> scope = new HashMap<>(inScope);
        out.append("    ".repeat(depth)).append('<').append(element.getNodeName());
        NamedNodeMap attributes = element.getAttributes();
        List<Attr> plain = new ArrayList<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            String name = attribute.getNodeName();
            if ("xmlns".equals(name) || name.startsWith("xmlns:")) {
                declare(out, scope, "xmlns".equals(name) ? "" : name.substring("xmlns:".length()),
                        attribute.getValue());
            } else {
                plain.add(attribute);
            }
        }
        declare(out, scope, element.getPrefix(), element.getNamespaceURI());
        for (Attr attribute : plain) {
            if (attribute.getPrefix() != null) {
                declare(out, scope, attribute.getPrefix(), attribute.getNamespaceURI());
            }
            out.append(' ').append(attribute.getNodeName()).append("=\"")
                    .append(escape(attribute.getValue(), true)).append('"');
        }

        List<Node> content = new ArrayList<>();
        boolean hasElements = false;
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element) {
                content.add(node);
                hasElements = true;
            } else if ((node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !node.getNodeValue().isBlank()) {
                content.add(node);
            }
        }
        if (content.isEmpty()) {
            out.append("/>\n");
            return;
        }
        if (!hasElements) {
            out.append('>');
            for (Node node : content) {
                out.append(escape(node.getNodeValue().strip(), false));
            }
            out.append("</").append(element.getNodeName()).append(">\n");
            return;
        }
        out.append(">\n");
        for (Node node : content) {
            if (node instanceof Element child) {
                writeElement(out, child, scope, depth + 1);
            } else {
                out.append("    ".repeat(depth + 1)).append(escape(node.getNodeValue().strip(), false)).append('\n');
            }
        }
        out.append("    ".repeat(depth)).append("</").append(element.getNodeName()).append(">\n");
    }

    private static void declare(StringBuilder out, Map<String, String> scope, String prefix, String uri) {
        String key = prefix == null ? "" : prefix;
        String value = uri == null ? "" : uri;
        if (value.equals(scope.getOrDefault(key, ""))) {
            return;
        }
        scope.put(key, value);
        out.append(' ').append(key.isEmpty() ? "xmlns" : "xmlns:" + key).append("=\"")
                .append(escape(value, true)).append('"');
    }

    private static String escape(String value, boolean attribute) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append(attribute ? "&quot;" : "\"");
                case '\n' -> escaped.append(attribute ? "&#10;" : "\n");
                case '\r' -> escaped.append("&#13;");
                case '\t' -> escaped.append(attribute ? "&#9;" : "\t");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
