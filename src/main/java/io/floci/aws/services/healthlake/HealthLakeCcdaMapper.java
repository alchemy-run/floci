package io.floci.aws.services.healthlake;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.apache.velocity.runtime.resource.util.StringResourceRepository;
import org.apache.velocity.runtime.resource.util.StringResourceRepositoryImpl;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * HealthLake's C-CDA transformation profiles: Velocity templates, each file of the profile's mapping a template
 * ({@code Resources/Patient.vm}, ...). floci renders every template under {@code Resources/} once a document, with the
 * document as {@code $msg} (and {@code $input}) and the helpers HealthLake registers, and reads what it writes as
 * FHIR: one resource, a list of them, or a Bundle whose entries are. A template may {@code #parse} the profile's
 * other files.
 */
final class HealthLakeCcdaMapper {

    static final String STARTER = "ccda-starter-kit-v1";
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA).enable(JsonReadFeature.ALLOW_JAVA_COMMENTS).build();

    private HealthLakeCcdaMapper() {
    }

    /** A C-CDA document as maps: attributes and child elements as keys, repeated ones lists, text under {@code _}. */
    static Map<String, Object> parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(xml)));
            Element root = document.getDocumentElement();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(local(root), element(root));
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("The document is not well-formed XML: " + e.getMessage());
        }
    }

    private static String local(Node node) {
        return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
    }

    private static Object element(Element element) {
        Map<String, Object> map = new LinkedHashMap<>();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String name = local(attribute);
            if (attribute.getNodeName().startsWith("xmlns")) {
                continue;
            }
            map.put(name, attribute.getNodeValue());
        }
        StringBuilder text = new StringBuilder();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String name = local(child);
                Object value = element((Element) child);
                Object existing = map.get(name);
                if (existing == null) {
                    map.put(name, value);
                } else if (existing instanceof List<?> list && map.containsKey("__list_" + name)) {
                    @SuppressWarnings("unchecked")
                    List<Object> items = (List<Object>) list;
                    items.add(value);
                } else {
                    List<Object> items = new ArrayList<>();
                    items.add(existing);
                    items.add(value);
                    map.put(name, items);
                    map.put("__list_" + name, Boolean.TRUE);
                }
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            }
        }
        map.keySet().removeIf(key -> key.startsWith("__list_"));
        String trimmed = text.toString().trim();
        if (!trimmed.isEmpty()) {
            map.put("_", trimmed);
        }
        return map;
    }

    /** Renders a profile's templates over one document; returns the FHIR resources they write. */
    static List<JsonNode> convert(Map<String, String> mapping, String xml) {
        Map<String, Object> document = parse(xml);
        StringResourceRepository repository = new StringResourceRepositoryImpl();
        mapping.forEach((name, content) -> repository.putStringResource(name, content));
        VelocityEngine engine = new VelocityEngine();
        engine.setProperty(RuntimeConstants.RESOURCE_LOADERS, "string");
        engine.setProperty("resource.loader.string.class", StringResourceLoader.class.getName());
        engine.setProperty("resource.loader.string.repository.static", "false");
        engine.setProperty("resource.loader.string.repository.name", "healthlake");
        engine.setProperty(RuntimeConstants.RUNTIME_REFERENCES_STRICT, false);
        engine.setProperty(RuntimeConstants.MAX_NUMBER_LOOPS, 100_000);
        engine.setApplicationAttribute("healthlake", repository);
        engine.init();
        List<JsonNode> resources = new ArrayList<>();
        for (String name : new TreeMap<>(mapping).keySet()) {
            if (!name.startsWith("Resources/") || !name.endsWith(".vm")) {
                continue;
            }
            VelocityContext context = new VelocityContext();
            context.put("msg", document);
            context.put("input", document);
            HealthLakeVelocityHelpers.all().forEach(context::put);
            StringWriter out = new StringWriter();
            try {
                engine.getTemplate(name, "UTF-8").merge(context, out);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Template " + name + " failed: " + e.getMessage());
            }
            String written = out.toString().trim();
            if (written.isEmpty()) {
                continue;
            }
            JsonNode parsed;
            try {
                parsed = LENIENT.readTree(written);
            } catch (Exception e) {
                throw new IllegalArgumentException("Template " + name + " did not write JSON: " + e.getMessage());
            }
            collect(parsed, resources);
        }
        return resources;
    }

    private static void collect(JsonNode node, List<JsonNode> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collect(item, out));
            return;
        }
        if ("Bundle".equals(node.path("resourceType").asText())) {
            node.path("entry").forEach(entry -> collect(entry.path("resource"), out));
            return;
        }
        if (node.isObject() && node.hasNonNull("resourceType")) {
            out.add(node);
        }
    }

    /** The files of the AWS starter profile as floci ships it: patient, problems, allergies, results and vitals. */
    static Map<String, String> starter() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Resources/Patient.vm", """
                #set($patientRole = $msg.ClinicalDocument.recordTarget.patientRole)
                #set($patient = $patientRole.patient)
                #set($id = $specializedHelper.generateUUID($patientRole.id))
                #set($name = $arrayHelper.elementAt($arrayHelper.toArray($patient.name), 0))
                #set($gender = $patient.administrativeGenderCode.code)
                {
                  "resourceType": "Patient",
                  "id": "$id",
                  "identifier": [
                #foreach($identifier in $arrayHelper.toArray($patientRole.id))
                    {"system": "$cdaHelper.getSystemUrl($identifier.root, false)", "value": "$!identifier.extension"},
                #end
                  ],
                  "name": [{
                    "family": "$!stringHelper.escapeJsonString($stringHelper.parseReferenceData($name.family))",
                    "given": [
                #foreach($given in $arrayHelper.toArray($name.given))
                      "$!stringHelper.escapeJsonString($stringHelper.parseReferenceData($given))",
                #end
                    ]
                  }],
                #if($conditionalHelper.eq($gender, "M"))
                  "gender": "male",
                #elseif($conditionalHelper.eq($gender, "F"))
                  "gender": "female",
                #elseif($gender)
                  "gender": "unknown",
                #end
                #if($patient.birthTime.value)
                  "birthDate": "$dateHelper.addHyphensDate($arrayHelper.strSlice($patient.birthTime.value, 0, 8))",
                #end
                  "address": [
                #foreach($addr in $arrayHelper.toArray($patientRole.addr))
                    {
                      "line": [#foreach($line in $arrayHelper.toArray($addr.streetAddressLine))"$!stringHelper.escapeJsonString($stringHelper.parseReferenceData($line))",#end],
                      "city": "$!stringHelper.escapeJsonString($stringHelper.parseReferenceData($addr.city))",
                      "state": "$!stringHelper.parseReferenceData($addr.state)",
                      "postalCode": "$!stringHelper.parseReferenceData($addr.postalCode)"
                    },
                #end
                  ]
                }
                """);
        files.put("Resources/Condition.vm", section("2.16.840.1.113883.10.20.22.2.5.1", """
                #set($problem = $entry.act.entryRelationship.observation)
                #if($problem.value.code)
                    {
                      "resourceType": "Condition",
                      "id": "$specializedHelper.generateUUID($problem)",
                      "subject": {"reference": "Patient/$patientId"},
                      "clinicalStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-clinical", "code": "active"}]},
                      "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-category", "code": "problem-list-item"}]}],
                      "code": {"coding": [{"system": "$cdaHelper.getSystemUrl($problem.value.codeSystem, false)", "code": "$problem.value.code", "display": "$!stringHelper.escapeJsonString($problem.value.displayName)"}]}
                #if($problem.effectiveTime.low.value)
                      ,"onsetDateTime": "$dateHelper.formatAsDateTime($problem.effectiveTime.low.value)"
                #end
                    },
                #end
                """));
        files.put("Resources/AllergyIntolerance.vm", section("2.16.840.1.113883.10.20.22.2.6.1", """
                #set($allergy = $entry.act.entryRelationship.observation)
                #set($substance = $allergy.participant.participantRole.playingEntity.code)
                #if($substance.code)
                    {
                      "resourceType": "AllergyIntolerance",
                      "id": "$specializedHelper.generateUUID($allergy)",
                      "patient": {"reference": "Patient/$patientId"},
                      "clinicalStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical", "code": "active"}]},
                      "code": {"coding": [{"system": "$cdaHelper.getSystemUrl($substance.codeSystem, false)", "code": "$substance.code", "display": "$!stringHelper.escapeJsonString($substance.displayName)"}]}
                    },
                #end
                """));
        files.put("Resources/Observation.vm", """
                #set($patientId = $specializedHelper.generateUUID($msg.ClinicalDocument.recordTarget.patientRole.id))
                [
                #foreach($wrapped in $cdaHelper.getAllCdaSectionsByTemplateId($msg, "2.16.840.1.113883.10.20.22.2.3.1", "2.16.840.1.113883.10.20.22.2.4.1"))
                #foreach($section in $wrapped.values())
                #set($category = "laboratory")
                #if($wrapped.containsKey("_2_16_840_1_113883_10_20_22_2_4_1"))#set($category = "vital-signs")#end
                #foreach($entry in $arrayHelper.toArray($section.entry))
                #foreach($component in $arrayHelper.toArray($entry.organizer.component))
                #set($observation = $component.observation)
                #if($observation.code.code)
                  {
                    "resourceType": "Observation",
                    "id": "$specializedHelper.generateUUID($observation)",
                    "status": "final",
                    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "$category", "display": "$specializedHelper.getObservationCategoryDisplayFromCode($category)"}]}],
                    "subject": {"reference": "Patient/$patientId"},
                    "code": {"coding": [{"system": "$cdaHelper.getSystemUrl($observation.code.codeSystem, false)", "code": "$observation.code.code", "display": "$!stringHelper.escapeJsonString($observation.code.displayName)"}]}
                #if($observation.effectiveTime.value)
                    ,"effectiveDateTime": "$dateHelper.formatAsDateTime($observation.effectiveTime.value)"
                #end
                #if($observation.value.value && !$mathHelper.isNaN($observation.value.value))
                    ,"valueQuantity": {"value": $observation.value.value, "unit": "$!observation.value.unit", "system": "http://unitsofmeasure.org", "code": "$!observation.value.unit"}
                #end
                  },
                #end
                #end
                #end
                #end
                #end
                ]
                """);
        return files;
    }

    /** A template that writes one resource a section entry, for the section with a template id. */
    private static String section(String templateId, String body) {
        return "#set($patientId = $specializedHelper.generateUUID($msg.ClinicalDocument.recordTarget.patientRole.id))\n"
                + "[\n"
                + "#foreach($wrapped in $cdaHelper.getAllCdaSectionsByTemplateId($msg, \"" + templateId + "\"))\n"
                + "#set($section = $wrapped.get(\"" + HealthLakeVelocityHelpers.CdaHelper.normalize(templateId)
                + "\"))\n"
                + "#foreach($entry in $arrayHelper.toArray($section.entry))\n"
                + body
                + "#end\n"
                + "#end\n"
                + "]\n";
    }
}
