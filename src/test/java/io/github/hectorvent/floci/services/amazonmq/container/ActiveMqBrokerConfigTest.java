package io.github.hectorvent.floci.services.amazonmq.container;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveMqBrokerConfigTest {

    private static final List<ActiveMqBrokerConfig.Credential> USERS = List.of(
            new ActiveMqBrokerConfig.Credential("alchemyadmin", "Super<Secret>&\"Pass", true, List.of("admins")),
            new ActiveMqBrokerConfig.Credential("tenant", "TenantPassw0rd!", false, null));

    private static Element broker(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList brokers = doc.getElementsByTagNameNS(ActiveMqBrokerConfig.CORE_NS, "broker");
        assertEquals(1, brokers.getLength());
        return (Element) brokers.item(0);
    }

    private static List<String> childNames(Element parent) {
        List<String> names = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                names.add(element.getLocalName());
            }
        }
        return names;
    }

    @Test
    void defaultConfigurationAuthenticatesEveryUserOnEveryTransport() throws Exception {
        String xml = ActiveMqBrokerConfig.activemqXml(USERS, null);
        Element broker = broker(xml);

        assertEquals(List.of("destinationPolicy", "managementContext", "persistenceAdapter", "plugins",
                "shutdownHooks", "systemUsage", "transportConnectors"), childNames(broker));

        NodeList users = broker.getElementsByTagNameNS(ActiveMqBrokerConfig.CORE_NS, "authenticationUser");
        assertEquals(2, users.getLength());
        Element admin = (Element) users.item(0);
        assertEquals("alchemyadmin", admin.getAttribute("username"));
        assertEquals("Super<Secret>&\"Pass", admin.getAttribute("password"));
        assertEquals("admins", admin.getAttribute("groups"));
        assertEquals("", ((Element) users.item(1)).getAttribute("groups"));

        NodeList connectors = broker.getElementsByTagNameNS(ActiveMqBrokerConfig.CORE_NS, "transportConnector");
        List<String> uris = new ArrayList<>();
        for (int i = 0; i < connectors.getLength(); i++) {
            uris.add(((Element) connectors.item(i)).getAttribute("uri").replaceAll("\\?.*", ""));
        }
        assertEquals(List.of("tcp://0.0.0.0:61616", "amqp://0.0.0.0:5672", "stomp://0.0.0.0:61613",
                "mqtt://0.0.0.0:1883", "ws://0.0.0.0:61614"), uris);
        assertTrue(xml.contains("jetty.xml"));
    }

    @Test
    void configurationRevisionContributesOnlyPermittedElements() throws Exception {
        String revision = """
                <broker xmlns="http://activemq.apache.org/schema/core" schedulerSupport="true" brokerName="evil"
                        dataDirectory="/tmp/elsewhere">
                  <transportConnectors>
                    <transportConnector name="rogue" uri="tcp://0.0.0.0:9999"/>
                  </transportConnectors>
                  <plugins>
                    <simpleAuthenticationPlugin anonymousAccessAllowed="true"/>
                    <authorizationPlugin>
                      <map><authorizationMap><authorizationEntries>
                        <authorizationEntry queue="&gt;" read="admins" write="admins" admin="admins"/>
                      </authorizationEntries></authorizationMap></map>
                    </authorizationPlugin>
                  </plugins>
                  <destinations>
                    <queue physicalName="orders"/>
                  </destinations>
                </broker>
                """;
        Element broker = broker(ActiveMqBrokerConfig.activemqXml(USERS, revision));

        assertEquals("true", broker.getAttribute("schedulerSupport"));
        assertEquals("localhost", broker.getAttribute("brokerName"));
        assertEquals("${activemq.data}", broker.getAttribute("dataDirectory"));
        assertEquals(List.of("destinationPolicy", "destinations", "managementContext", "persistenceAdapter",
                "plugins", "shutdownHooks", "systemUsage", "transportConnectors"), childNames(broker));

        Element plugins = (Element) broker.getElementsByTagNameNS(ActiveMqBrokerConfig.CORE_NS, "plugins").item(0);
        assertEquals(List.of("authorizationPlugin", "simpleAuthenticationPlugin"), childNames(plugins));
        Element authentication = (Element) plugins
                .getElementsByTagNameNS(ActiveMqBrokerConfig.CORE_NS, "simpleAuthenticationPlugin").item(0);
        assertEquals("false", authentication.getAttribute("anonymousAccessAllowed"));

        NodeList connectors = broker.getElementsByTagNameNS(ActiveMqBrokerConfig.CORE_NS, "transportConnector");
        for (int i = 0; i < connectors.getLength(); i++) {
            assertFalse(((Element) connectors.item(i)).getAttribute("name").equals("rogue"));
        }
    }

    @Test
    void anUnparseableRevisionFallsBackToTheDefaults() throws Exception {
        Element broker = broker(ActiveMqBrokerConfig.activemqXml(USERS, "<broker"));
        assertTrue(childNames(broker).contains("plugins"));
    }

    @Test
    void jettyRealmListsOnlyConsoleUsersAsAdmins() {
        String realm = ActiveMqBrokerConfig.jettyRealm(List.of(
                new ActiveMqBrokerConfig.Credential("alchemyadmin", "Back\\slashPass1", true, null),
                new ActiveMqBrokerConfig.Credential("tenant", "TenantPassw0rd!", false, null)));
        assertEquals("alchemyadmin: Back\\\\slashPass1, admin\n", realm);
    }
}
