package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.route53.Route53Service.CreateZoneResult;
import io.github.hectorvent.floci.services.route53.model.AliasTarget;
import io.github.hectorvent.floci.services.route53.model.ChangeInfo;
import io.github.hectorvent.floci.services.route53.model.HealthCheck;
import io.github.hectorvent.floci.services.route53.model.HealthCheckConfig;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.QueryLoggingConfig;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.ZoneVpc;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/2013-04-01")
public class Route53Controller {

    private static final String NS = AwsNamespaces.ROUTE53;
    private static final String XML = "application/xml";

    private static final XMLInputFactory XML_FACTORY;

    static {
        XML_FACTORY = XMLInputFactory.newInstance();
        XML_FACTORY.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        XML_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XML_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    @Inject
    Route53Service service;

    // ── Hosted Zones ──────────────────────────────────────────────────────────

    @POST
    @Path("/hostedzone")
    public Response createHostedZone(String body) {
        try {
            String name = XmlParser.extractFirst(body, "Name", null);
            String callerRef = XmlParser.extractFirst(body, "CallerReference", null);
            String comment = XmlParser.extractFirst(body, "Comment", null);
            boolean privateZone = "true".equalsIgnoreCase(
                    XmlParser.extractFirst(body, "PrivateZone", "false"));

            if (name == null || callerRef == null) {
                throw new AwsException("InvalidInput", "Name and CallerReference are required.", 400);
            }

            String vpcId = XmlParser.extractFirst(body, "VPCId", null);
            String vpcRegion = XmlParser.extractFirst(body, "VPCRegion", null);
            CreateZoneResult result = service.createHostedZone(
                    name, callerRef, comment, privateZone, vpcId, vpcRegion);
            String xml = new XmlBuilder()
                    .start("CreateHostedZoneResponse", NS)
                    .raw(xmlHostedZone(result.zone()))
                    .raw(xmlChangeInfo(result.change()))
                    .raw(xmlDelegationSet())
                    .end("CreateHostedZoneResponse")
                    .build();

            return Response.created(URI.create("/2013-04-01/hostedzone/" + result.zone().getId()))
                    .type(XML)
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzone/{Id}")
    public Response getHostedZone(@PathParam("Id") String id) {
        try {
            HostedZone zone = service.getHostedZone(id);
            String xml = new XmlBuilder()
                    .start("GetHostedZoneResponse", NS)
                    .raw(xmlHostedZone(zone))
                    .raw(xmlDelegationSet())
                    .raw(xmlVpcs(zone))
                    .end("GetHostedZoneResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/hostedzone/{Id}")
    public Response deleteHostedZone(@PathParam("Id") String id) {
        try {
            ChangeInfo change = service.deleteHostedZone(id);
            String xml = new XmlBuilder()
                    .start("DeleteHostedZoneResponse", NS)
                    .raw(xmlChangeInfo(change))
                    .end("DeleteHostedZoneResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/hostedzone/{Id}")
    public Response updateHostedZoneComment(@PathParam("Id") String id, String body) {
        try {
            String comment = XmlParser.extractFirst(body, "Comment", "");
            HostedZone zone = service.updateHostedZoneComment(id, comment);
            String xml = new XmlBuilder()
                    .start("UpdateHostedZoneCommentResponse", NS)
                    .raw(xmlHostedZone(zone))
                    .end("UpdateHostedZoneCommentResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzone")
    public Response listHostedZones(@QueryParam("marker") String marker,
                                     @QueryParam("maxitems") @DefaultValue("100") int maxItems) {
        try {
            List<HostedZone> zones = service.listHostedZones(marker, maxItems);
            long total = service.getHostedZoneCount();
            boolean truncated = zones.size() == maxItems && zones.size() < total;

            XmlBuilder xml = new XmlBuilder()
                    .start("ListHostedZonesResponse", NS)
                    .start("HostedZones");
            for (HostedZone zone : zones) {
                xml.raw(xmlHostedZone(zone));
            }
            xml.end("HostedZones")
               .elem("Marker", marker != null ? marker : "")
               .elem("IsTruncated", String.valueOf(truncated));
            if (truncated && !zones.isEmpty()) {
                xml.elem("NextMarker", zones.get(zones.size() - 1).getId());
            }
            xml.elem("MaxItems", String.valueOf(maxItems))
               .end("ListHostedZonesResponse");

            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzonesbyname")
    public Response listHostedZonesByName(@Context UriInfo uriInfo) {
        try {
            String dnsName = queryParam(uriInfo, "dnsname", "DNSName", "dnsName");
            String hostedZoneId = queryParam(uriInfo, "hostedzoneid", "HostedZoneId", "hostedZoneId");
            int maxItems = queryInt(uriInfo, 100, "maxitems", "MaxItems", "maxItems");
            List<HostedZone> fetched = service.listHostedZonesByName(dnsName, hostedZoneId, maxItems + 1);
            boolean truncated = fetched.size() > maxItems;
            List<HostedZone> zones = truncated ? fetched.subList(0, maxItems) : fetched;

            XmlBuilder xml = new XmlBuilder()
                    .start("ListHostedZonesByNameResponse", NS)
                    .start("HostedZones");
            for (HostedZone zone : zones) {
                xml.raw(xmlHostedZone(zone));
            }
            xml.end("HostedZones")
               .elem("IsTruncated", String.valueOf(truncated))
               .elem("MaxItems", String.valueOf(maxItems));
            if (dnsName != null && !dnsName.isEmpty()) {
                xml.elem("DNSName", dnsName);
            }
            if (hostedZoneId != null && !hostedZoneId.isEmpty()) {
                xml.elem("HostedZoneId", hostedZoneId);
            }
            if (truncated && !zones.isEmpty()) {
                HostedZone next = fetched.get(maxItems);
                xml.elem("NextDNSName", next.getName())
                   .elem("NextHostedZoneId", next.getId());
            }
            xml.end("ListHostedZonesByNameResponse");

            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzonecount")
    public Response getHostedZoneCount() {
        String xml = new XmlBuilder()
                .start("GetHostedZoneCountResponse", NS)
                .elem("HostedZoneCount", service.getHostedZoneCount())
                .end("GetHostedZoneCountResponse")
                .build();
        return Response.ok(xml, XML).build();
    }

    // ── Resource Record Sets ──────────────────────────────────────────────────

    @POST
    @Path("/hostedzone/{Id}/rrset")
    public Response changeResourceRecordSets(@PathParam("Id") String id, String body) {
        try {
            String comment = XmlParser.extractFirst(body, "Comment", null);
            List<Map<String, Object>> changes = parseChangeBatch(body);
            ChangeInfo change = service.changeResourceRecordSets(id, changes, comment);
            String xml = new XmlBuilder()
                    .start("ChangeResourceRecordSetsResponse", NS)
                    .raw(xmlChangeInfo(change))
                    .end("ChangeResourceRecordSetsResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzone/{Id}/rrset")
    public Response listResourceRecordSets(@PathParam("Id") String id,
                                            @QueryParam("name") String startName,
                                            @QueryParam("type") String startType,
                                            @QueryParam("maxitems") @DefaultValue("300") int maxItems) {
        try {
            List<ResourceRecordSet> fetched = service.listResourceRecordSets(id, startName, startType, maxItems + 1);
            boolean truncated = fetched.size() > maxItems;
            List<ResourceRecordSet> records = truncated ? fetched.subList(0, maxItems) : fetched;

            XmlBuilder xml = new XmlBuilder()
                    .start("ListResourceRecordSetsResponse", NS)
                    .start("ResourceRecordSets");
            for (ResourceRecordSet rrs : records) {
                xml.raw(xmlResourceRecordSet(rrs));
            }
            xml.end("ResourceRecordSets")
               .elem("IsTruncated", String.valueOf(truncated));
            if (truncated) {
                ResourceRecordSet next = fetched.get(maxItems);
                xml.elem("NextRecordName", next.getName())
                   .elem("NextRecordType", next.getType());
            }
            xml.elem("MaxItems", String.valueOf(maxItems))
               .end("ListResourceRecordSetsResponse");

            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Changes ───────────────────────────────────────────────────────────────

    @GET
    @Path("/change/{Id}")
    public Response getChange(@PathParam("Id") String id) {
        try {
            ChangeInfo change = service.getChange(id);
            String xml = new XmlBuilder()
                    .start("GetChangeResponse", NS)
                    .raw(xmlChangeInfo(change))
                    .end("GetChangeResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Health Checks ─────────────────────────────────────────────────────────

    @POST
    @Path("/healthcheck")
    public Response createHealthCheck(String body) {
        try {
            String callerRef = XmlParser.extractFirst(body, "CallerReference", null);
            if (callerRef == null) {
                throw new AwsException("InvalidInput", "CallerReference is required.", 400);
            }
            HealthCheckConfig cfg = parseHealthCheckConfig(body);
            HealthCheck hc = service.createHealthCheck(callerRef, cfg);
            String xml = new XmlBuilder()
                    .start("CreateHealthCheckResponse", NS)
                    .raw(xmlHealthCheck(hc))
                    .end("CreateHealthCheckResponse")
                    .build();
            return Response.created(URI.create("/2013-04-01/healthcheck/" + hc.getId()))
                    .type(XML)
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/healthcheck/{HealthCheckId}")
    public Response getHealthCheck(@PathParam("HealthCheckId") String id) {
        try {
            HealthCheck hc = service.getHealthCheck(id);
            String xml = new XmlBuilder()
                    .start("GetHealthCheckResponse", NS)
                    .raw(xmlHealthCheck(hc))
                    .end("GetHealthCheckResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/healthcheck/{HealthCheckId}")
    public Response deleteHealthCheck(@PathParam("HealthCheckId") String id) {
        try {
            service.deleteHealthCheck(id);
            return Response.ok("", XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/healthcheck")
    public Response listHealthChecks(@QueryParam("marker") String marker,
                                      @QueryParam("maxitems") @DefaultValue("100") int maxItems) {
        try {
            List<HealthCheck> checks = service.listHealthChecks(marker, maxItems);
            boolean truncated = checks.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("ListHealthChecksResponse", NS)
                    .start("HealthChecks");
            for (HealthCheck hc : checks) {
                xml.raw(xmlHealthCheck(hc));
            }
            xml.end("HealthChecks")
               .elem("Marker", marker != null ? marker : "")
               .elem("IsTruncated", String.valueOf(truncated))
               .elem("MaxItems", String.valueOf(maxItems))
               .end("ListHealthChecksResponse");

            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/healthcheck/{HealthCheckId}")
    public Response updateHealthCheck(@PathParam("HealthCheckId") String id, String body) {
        try {
            HealthCheckConfig cfg = parseHealthCheckConfig(body);
            HealthCheck hc = service.updateHealthCheck(id, cfg);
            String xml = new XmlBuilder()
                    .start("UpdateHealthCheckResponse", NS)
                    .raw(xmlHealthCheck(hc))
                    .end("UpdateHealthCheckResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    @GET
    @Path("/tags/{ResourceType}/{ResourceId}")
    public Response listTagsForResource(@PathParam("ResourceType") String type,
                                         @PathParam("ResourceId") String resourceId) {
        try {
            Map<String, String> tags = service.listTagsForResource(type, resourceId);
            XmlBuilder xml = new XmlBuilder()
                    .start("ListTagsForResourceResponse", NS)
                    .start("ResourceTagSet")
                    .elem("ResourceType", type)
                    .elem("ResourceId", resourceId)
                    .start("Tags");
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                xml.start("Tag")
                   .elem("Key", entry.getKey())
                   .elem("Value", entry.getValue())
                   .end("Tag");
            }
            xml.end("Tags").end("ResourceTagSet").end("ListTagsForResourceResponse");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/tags/{ResourceType}/{ResourceId}")
    public Response changeTagsForResource(@PathParam("ResourceType") String type,
                                           @PathParam("ResourceId") String resourceId,
                                           String body) {
        try {
            List<Map<String, String>> addTags = XmlParser.extractGroups(body, "Tag").stream()
                    .filter(g -> g.containsKey("Key"))
                    .map(g -> Map.of("Key", g.get("Key"), "Value", g.getOrDefault("Value", "")))
                    .toList();
            List<String> removeTagKeys = parseRemoveTagKeys(body);
            service.changeTagsForResource(type, resourceId, addTags, removeTagKeys);
            String xml = new XmlBuilder()
                    .start("ChangeTagsForResourceResponse", NS)
                    .end("ChangeTagsForResourceResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Limits ────────────────────────────────────────────────────────────────

    @GET
    @Path("/accountlimit/{Type}")
    public Response getAccountLimit(@PathParam("Type") String type) {
        long value = switch (type) {
            case "MAX_HEALTH_CHECKS_BY_OWNER" -> 200L;
            case "MAX_HOSTED_ZONES_BY_OWNER" -> 500L;
            case "MAX_REUSABLE_DELEGATION_SETS_BY_OWNER" -> 100L;
            case "MAX_TRAFFIC_POLICY_INSTANCES_BY_OWNER" -> 5L;
            case "MAX_TRAFFIC_POLICIES_BY_OWNER" -> 50L;
            default -> 100L;
        };
        String xml = new XmlBuilder()
                .start("GetAccountLimitResponse", NS)
                .start("Limit")
                .elem("Type", type)
                .elem("Value", value)
                .end("Limit")
                .elem("Count", 0L)
                .end("GetAccountLimitResponse")
                .build();
        return Response.ok(xml, XML).build();
    }

    @GET
    @Path("/healthcheck/{HealthCheckId}/status")
    public Response getHealthCheckStatus(@PathParam("HealthCheckId") String id) {
        try {
            service.getHealthCheck(id);
            String now = Instant.now().toString();
            String xml = new XmlBuilder()
                    .start("GetHealthCheckStatusResponse", NS)
                    .start("HealthCheckObservations")
                    .start("HealthCheckObservation")
                    .elem("IPAddress", "1.2.3.4")
                    .elem("Region", "us-east-1")
                    .start("StatusReport")
                    .elem("Status", "Success: HTTP Status Code 200, OK")
                    .elem("CheckedTime", now)
                    .end("StatusReport")
                    .end("HealthCheckObservation")
                    .end("HealthCheckObservations")
                    .end("GetHealthCheckStatusResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzone/{Id}/dnssec")
    public Response getDnssec(@PathParam("Id") String id) {
        try {
            service.getHostedZone(id);
            String xml = new XmlBuilder()
                    .start("GetDNSSECResponse", NS)
                    .start("Status")
                    .elem("ServeSignature", "NOT_SIGNING")
                    .elem("StatusMessage", "Zone is not signing")
                    .end("Status")
                    .start("KeySigningKeys")
                    .end("KeySigningKeys")
                    .end("GetDNSSECResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/hostedzone/{Id}/associatevpc")
    public Response associateVpc(@PathParam("Id") String id, String body) {
        try {
            ChangeInfo change = service.associateVpc(id,
                    XmlParser.extractFirst(body, "VPCId", null),
                    XmlParser.extractFirst(body, "VPCRegion", null));
            String xml = new XmlBuilder()
                    .start("AssociateVPCWithHostedZoneResponse", NS)
                    .raw(xmlChangeInfo(change))
                    .end("AssociateVPCWithHostedZoneResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/hostedzone/{Id}/disassociatevpc")
    public Response disassociateVpc(@PathParam("Id") String id, String body) {
        try {
            ChangeInfo change = service.disassociateVpc(id,
                    XmlParser.extractFirst(body, "VPCId", null),
                    XmlParser.extractFirst(body, "VPCRegion", null));
            String xml = new XmlBuilder()
                    .start("DisassociateVPCFromHostedZoneResponse", NS)
                    .raw(xmlChangeInfo(change))
                    .end("DisassociateVPCFromHostedZoneResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/hostedzone/{Id}/authorizevpcassociation")
    public Response createVpcAssociationAuthorization(@PathParam("Id") String id, String body) {
        try {
            ZoneVpc vpc = service.createVpcAssociationAuthorization(id,
                    XmlParser.extractFirst(body, "VPCId", null),
                    XmlParser.extractFirst(body, "VPCRegion", null));
            String xml = new XmlBuilder()
                    .start("CreateVPCAssociationAuthorizationResponse", NS)
                    .raw(xmlVpc(vpc))
                    .end("CreateVPCAssociationAuthorizationResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/hostedzone/{Id}/deauthorizevpcassociation")
    public Response deleteVpcAssociationAuthorization(@PathParam("Id") String id, String body) {
        try {
            service.deleteVpcAssociationAuthorization(id, XmlParser.extractFirst(body, "VPCId", null));
            String xml = new XmlBuilder()
                    .start("DeleteVPCAssociationAuthorizationResponse", NS)
                    .end("DeleteVPCAssociationAuthorizationResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzone/{Id}/authorizevpcassociation")
    public Response listVpcAssociationAuthorizations(@PathParam("Id") String id) {
        try {
            List<ZoneVpc> vpcs = service.listVpcAssociationAuthorizations(id);
            XmlBuilder xml = new XmlBuilder()
                    .start("ListVPCAssociationAuthorizationsResponse", NS)
                    .elem("HostedZoneId", "/hostedzone/" + Route53Service.normalizeZoneId(id))
                    .start("VPCs");
            for (ZoneVpc vpc : vpcs) {
                xml.raw(xmlVpc(vpc));
            }
            xml.end("VPCs").end("ListVPCAssociationAuthorizationsResponse");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzonesbyvpc")
    public Response listHostedZonesByVpc(@QueryParam("vpcid") String vpcId,
                                          @QueryParam("vpcregion") String vpcRegion) {
        try {
            List<HostedZone> zones = service.listHostedZonesByVpc(vpcId, vpcRegion);
            XmlBuilder xml = new XmlBuilder()
                    .start("ListHostedZonesByVPCResponse", NS)
                    .start("HostedZoneSummaries");
            for (HostedZone zone : zones) {
                xml.start("HostedZoneSummary")
                   .elem("HostedZoneId", zone.getId())
                   .elem("Name", zone.getName())
                   .elem("Owner", "")
                   .end("HostedZoneSummary");
            }
            xml.end("HostedZoneSummaries").end("ListHostedZonesByVPCResponse");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/queryloggingconfig")
    public Response createQueryLoggingConfig(String body) {
        try {
            QueryLoggingConfig cfg = service.createQueryLoggingConfig(
                    XmlParser.extractFirst(body, "HostedZoneId", null),
                    XmlParser.extractFirst(body, "CloudWatchLogsLogGroupArn", null));
            String xml = new XmlBuilder()
                    .start("CreateQueryLoggingConfigResponse", NS)
                    .raw(xmlQueryLoggingConfig(cfg))
                    .end("CreateQueryLoggingConfigResponse")
                    .build();
            return Response.created(URI.create("/2013-04-01/queryloggingconfig/" + cfg.getId()))
                    .type(XML)
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/queryloggingconfig/{Id}")
    public Response getQueryLoggingConfig(@PathParam("Id") String id) {
        try {
            QueryLoggingConfig cfg = service.getQueryLoggingConfig(id);
            String xml = new XmlBuilder()
                    .start("GetQueryLoggingConfigResponse", NS)
                    .raw(xmlQueryLoggingConfig(cfg))
                    .end("GetQueryLoggingConfigResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/queryloggingconfig/{Id}")
    public Response deleteQueryLoggingConfig(@PathParam("Id") String id) {
        try {
            service.deleteQueryLoggingConfig(id);
            return Response.ok("", XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/queryloggingconfig")
    public Response listQueryLoggingConfigs(@QueryParam("hostedzoneid") String hostedZoneId) {
        try {
            List<QueryLoggingConfig> configs = service.listQueryLoggingConfigs(hostedZoneId);
            XmlBuilder xml = new XmlBuilder()
                    .start("ListQueryLoggingConfigsResponse", NS)
                    .start("QueryLoggingConfigs");
            for (QueryLoggingConfig cfg : configs) {
                xml.raw(xmlQueryLoggingConfig(cfg));
            }
            xml.end("QueryLoggingConfigs").end("ListQueryLoggingConfigsResponse");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/testdnsanswer")
    public Response testDnsAnswer(@QueryParam("hostedzoneid") String hostedZoneId,
                                   @QueryParam("recordname") String recordName,
                                   @QueryParam("recordtype") String recordType) {
        try {
            Route53Service.DnsAnswer answer = service.testDnsAnswer(hostedZoneId, recordName, recordType);
            XmlBuilder xml = new XmlBuilder()
                    .start("TestDNSAnswerResponse", NS)
                    .elem("Nameserver", service.getNameServers().get(0))
                    .elem("RecordName", answer.recordName())
                    .elem("RecordType", answer.recordType())
                    .start("RecordData");
            for (String value : answer.records()) {
                xml.elem("RecordDataEntry", value);
            }
            xml.end("RecordData")
               .elem("ResponseCode", answer.records().isEmpty() ? "NXDOMAIN" : "NOERROR")
               .elem("Protocol", "UDP")
               .end("TestDNSAnswerResponse");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/healthcheck/{HealthCheckId}/lastfailurereason")
    public Response getHealthCheckLastFailureReason(@PathParam("HealthCheckId") String id) {
        try {
            service.getHealthCheck(id);
            // A never-failed check has no observations. Distilled still requires
            // the HealthCheckObservations wrapper so `.length` is 0, not missing.
            String xml = new XmlBuilder()
                    .start("GetHealthCheckLastFailureReasonResponse", NS)
                    .start("HealthCheckObservations")
                    .end("HealthCheckObservations")
                    .end("GetHealthCheckLastFailureReasonResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzonelimit/{HostedZoneId}/{Type}")
    public Response getHostedZoneLimit(@PathParam("HostedZoneId") String zoneId,
                                        @PathParam("Type") String type) {
        try {
            service.getHostedZone(zoneId);
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
        long value = switch (type) {
            case "MAX_RRSETS_BY_ZONE" -> 10000L;
            case "MAX_VPCS_ASSOCIATED_BY_ZONE" -> 100L;
            default -> 100L;
        };
        String xml = new XmlBuilder()
                .start("GetHostedZoneLimitResponse", NS)
                .start("Limit")
                .elem("Type", type)
                .elem("Value", value)
                .end("Limit")
                .elem("Count", 0L)
                .end("GetHostedZoneLimitResponse")
                .build();
        return Response.ok(xml, XML).build();
    }

    // ── XML builders ──────────────────────────────────────────────────────────

    private String xmlHostedZone(HostedZone zone) {
        return new XmlBuilder()
                .start("HostedZone")
                .elem("Id", "/hostedzone/" + zone.getId())
                .elem("Name", zone.getName())
                .elem("CallerReference", zone.getCallerReference())
                .start("Config")
                .elem("Comment", zone.getComment())
                .elem("PrivateZone", String.valueOf(zone.isPrivateZone()))
                .end("Config")
                .elem("ResourceRecordSetCount", zone.getResourceRecordSetCount())
                .end("HostedZone")
                .build();
    }

    private String xmlChangeInfo(ChangeInfo change) {
        return new XmlBuilder()
                .start("ChangeInfo")
                .elem("Id", "/change/" + change.getId())
                .elem("Status", change.getStatus())
                .elem("SubmittedAt", change.getSubmittedAt())
                .elem("Comment", change.getComment())
                .end("ChangeInfo")
                .build();
    }

    private String xmlDelegationSet() {
        XmlBuilder xml = new XmlBuilder()
                .start("DelegationSet")
                .start("NameServers");
        for (String ns : service.getNameServers()) {
            xml.elem("NameServer", ns);
        }
        xml.end("NameServers").end("DelegationSet");
        return xml.build();
    }

    private String xmlVpcs(HostedZone zone) {
        if (zone.getVpcs() == null || zone.getVpcs().isEmpty()) {
            return "";
        }
        XmlBuilder xml = new XmlBuilder().start("VPCs");
        for (ZoneVpc vpc : zone.getVpcs()) {
            xml.raw(xmlVpc(vpc));
        }
        return xml.end("VPCs").build();
    }

    private String xmlVpc(ZoneVpc vpc) {
        return new XmlBuilder()
                .start("VPC")
                .elem("VPCId", vpc.getVpcId())
                .elem("VPCRegion", vpc.getVpcRegion())
                .end("VPC")
                .build();
    }

    private String xmlQueryLoggingConfig(QueryLoggingConfig cfg) {
        return new XmlBuilder()
                .start("QueryLoggingConfig")
                .elem("Id", cfg.getId())
                .elem("HostedZoneId", cfg.getHostedZoneId())
                .elem("CloudWatchLogsLogGroupArn", cfg.getCloudWatchLogsLogGroupArn())
                .end("QueryLoggingConfig")
                .build();
    }

    private String xmlResourceRecordSet(ResourceRecordSet rrs) {
        XmlBuilder xml = new XmlBuilder()
                .start("ResourceRecordSet")
                .elem("Name", rrs.getName())
                .elem("Type", rrs.getType());
        if (rrs.getSetIdentifier() != null) xml.elem("SetIdentifier", rrs.getSetIdentifier());
        if (rrs.getWeight() != null) xml.elem("Weight", rrs.getWeight());
        if (rrs.getRegion() != null) xml.elem("Region", rrs.getRegion());
        if (rrs.getFailover() != null) xml.elem("Failover", rrs.getFailover());
        if (rrs.getTtl() != null) xml.elem("TTL", rrs.getTtl());
        if (rrs.getRecords() != null && !rrs.getRecords().isEmpty()) {
            xml.start("ResourceRecords");
            for (ResourceRecord r : rrs.getRecords()) {
                xml.start("ResourceRecord").elem("Value", r.getValue()).end("ResourceRecord");
            }
            xml.end("ResourceRecords");
        }
        if (rrs.getAliasTarget() != null) {
            AliasTarget at = rrs.getAliasTarget();
            xml.start("AliasTarget")
               .elem("HostedZoneId", at.getHostedZoneId())
               .elem("DNSName", at.getDnsName())
               .elem("EvaluateTargetHealth", String.valueOf(at.isEvaluateTargetHealth()))
               .end("AliasTarget");
        }
        if (rrs.getHealthCheckId() != null) xml.elem("HealthCheckId", rrs.getHealthCheckId());
        if (rrs.getGeoContinentCode() != null || rrs.getGeoCountryCode() != null) {
            xml.start("GeoLocation");
            if (rrs.getGeoContinentCode() != null) xml.elem("ContinentCode", rrs.getGeoContinentCode());
            if (rrs.getGeoCountryCode() != null) xml.elem("CountryCode", rrs.getGeoCountryCode());
            if (rrs.getGeoSubdivisionCode() != null) xml.elem("SubdivisionCode", rrs.getGeoSubdivisionCode());
            xml.end("GeoLocation");
        }
        if (rrs.getCidrCollectionId() != null) {
            xml.start("CidrRoutingConfig")
               .elem("CollectionId", rrs.getCidrCollectionId())
               .elem("LocationName", rrs.getCidrLocationName())
               .end("CidrRoutingConfig");
        }
        if (rrs.getGeoProximityAwsRegion() != null || rrs.getGeoProximityLocalZoneGroup() != null) {
            xml.start("GeoProximityLocation");
            if (rrs.getGeoProximityAwsRegion() != null) xml.elem("AWSRegion", rrs.getGeoProximityAwsRegion());
            if (rrs.getGeoProximityLocalZoneGroup() != null) {
                xml.elem("LocalZoneGroup", rrs.getGeoProximityLocalZoneGroup());
            }
            if (rrs.getGeoProximityBias() != null) xml.elem("Bias", rrs.getGeoProximityBias().longValue());
            xml.end("GeoProximityLocation");
        }
        xml.end("ResourceRecordSet");
        return xml.build();
    }

    private String xmlHealthCheck(HealthCheck hc) {
        XmlBuilder xml = new XmlBuilder()
                .start("HealthCheck")
                .elem("Id", hc.getId())
                .elem("CallerReference", hc.getCallerReference());
        if (hc.getConfig() != null) {
            HealthCheckConfig cfg = hc.getConfig();
            xml.start("HealthCheckConfig")
               .elem("Type", cfg.getType())
               .elem("IPAddress", cfg.getIpAddress())
               .elem("Port", cfg.getPort() != null ? String.valueOf(cfg.getPort()) : null)
               .elem("ResourcePath", cfg.getResourcePath())
               .elem("FullyQualifiedDomainName", cfg.getFullyQualifiedDomainName())
               .elem("RequestInterval",
                       cfg.getRequestInterval() != null ? String.valueOf(cfg.getRequestInterval()) : null)
               .elem("FailureThreshold",
                       cfg.getFailureThreshold() != null ? String.valueOf(cfg.getFailureThreshold()) : null)
               .end("HealthCheckConfig");
        }
        xml.elem("HealthCheckVersion", hc.getHealthCheckVersion())
           .end("HealthCheck");
        return xml.build();
    }

    private Response xmlErrorResponse(AwsException e) {
        String xml = new XmlBuilder()
                .start("ErrorResponse", NS)
                .start("Error")
                .elem("Type", "Sender")
                .elem("Code", e.getErrorCode())
                .elem("Message", e.getMessage())
                .end("Error")
                .elem("RequestId", "00000000-0000-0000-0000-000000000000")
                .end("ErrorResponse")
                .build();
        return Response.status(e.getHttpStatus()).type(XML).entity(xml).build();
    }

    private static String queryParam(UriInfo uriInfo, String... names) {
        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();
        for (String name : names) {
            String value = params.getFirst(name);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        for (String key : params.keySet()) {
            for (String name : names) {
                if (key != null && key.equalsIgnoreCase(name)) {
                    String value = params.getFirst(key);
                    if (value != null && !value.isEmpty()) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private static int queryInt(UriInfo uriInfo, int fallback, String... names) {
        String raw = queryParam(uriInfo, names);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    // ── Request parsers ───────────────────────────────────────────────────────

    /**
     * Parses the ChangeBatch XML using StAX to correctly handle multiple Change elements,
     * each containing a ResourceRecordSet with its own set of ResourceRecord/Value children.
     */
    private List<Map<String, Object>> parseChangeBatch(String body) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (body == null || body.isEmpty()) return result;

        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            String currentAction = null;
            ResourceRecordSet currentRrs = null;
            List<ResourceRecord> currentRecords = null;
            AliasTarget currentAlias = null;
            int depth = 0;
            String currentElement = null;
            boolean inChangeBatch = false;
            boolean inChange = false;
            boolean inRrs = false;
            boolean inResourceRecords = false;
            boolean inAlias = false;
            boolean inHealthCheckConfig = false;
            boolean inGeo = false;
            boolean inCidr = false;
            boolean inGeoProx = false;

            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    currentElement = r.getLocalName();
                    switch (currentElement) {
                        case "ChangeBatch" -> inChangeBatch = true;
                        case "Change" -> {
                            if (inChangeBatch) {
                                inChange = true;
                                currentAction = null;
                                currentRrs = null;
                            }
                        }
                        case "Action" -> {
                            if (inChange && !inRrs) currentAction = r.getElementText();
                        }
                        case "ResourceRecordSet" -> {
                            if (inChange) {
                                inRrs = true;
                                currentRrs = new ResourceRecordSet();
                                currentRecords = new ArrayList<>();
                            }
                        }
                        case "ResourceRecords" -> { if (inRrs) inResourceRecords = true; }
                        case "AliasTarget" -> {
                            if (inRrs) {
                                inAlias = true;
                                currentAlias = new AliasTarget();
                            }
                        }
                        case "GeoLocation" -> { if (inRrs) inGeo = true; }
                        case "CidrRoutingConfig" -> { if (inRrs) inCidr = true; }
                        case "GeoProximityLocation" -> { if (inRrs) inGeoProx = true; }
                        case "Name" -> {
                            if (inRrs && !inAlias) {
                                String n = r.getElementText();
                                if (n != null && !n.endsWith(".")) n = n + ".";
                                if (currentRrs != null) currentRrs.setName(n);
                            }
                        }
                        case "Type" -> {
                            if (inRrs && !inAlias && !inHealthCheckConfig && currentRrs != null) {
                                currentRrs.setType(r.getElementText());
                            }
                        }
                        case "TTL" -> {
                            if (inRrs && currentRrs != null) {
                                try { currentRrs.setTtl(Long.parseLong(r.getElementText())); }
                                catch (NumberFormatException ignored) {}
                            }
                        }
                        case "Value" -> {
                            if (inResourceRecords && currentRecords != null) {
                                currentRecords.add(new ResourceRecord(r.getElementText()));
                            }
                        }
                        case "SetIdentifier" -> {
                            if (inRrs && currentRrs != null) currentRrs.setSetIdentifier(r.getElementText());
                        }
                        case "Weight" -> {
                            if (inRrs && currentRrs != null) {
                                try { currentRrs.setWeight(Long.parseLong(r.getElementText())); }
                                catch (NumberFormatException ignored) {}
                            }
                        }
                        case "Region" -> {
                            if (inRrs && !inAlias && currentRrs != null) currentRrs.setRegion(r.getElementText());
                        }
                        case "Failover" -> {
                            if (inRrs && currentRrs != null) currentRrs.setFailover(r.getElementText());
                        }
                        case "HealthCheckId" -> {
                            if (inRrs && !inHealthCheckConfig && currentRrs != null) {
                                currentRrs.setHealthCheckId(r.getElementText());
                            }
                        }
                        case "HostedZoneId" -> {
                            if (inAlias && currentAlias != null) currentAlias.setHostedZoneId(r.getElementText());
                        }
                        case "DNSName" -> {
                            if (inAlias && currentAlias != null) currentAlias.setDnsName(r.getElementText());
                        }
                        case "EvaluateTargetHealth" -> {
                            if (inAlias && currentAlias != null) {
                                currentAlias.setEvaluateTargetHealth(
                                        "true".equalsIgnoreCase(r.getElementText()));
                            }
                        }
                        case "ContinentCode" -> {
                            if (inGeo && currentRrs != null) currentRrs.setGeoContinentCode(r.getElementText());
                        }
                        case "CountryCode" -> {
                            if (inGeo && currentRrs != null) currentRrs.setGeoCountryCode(r.getElementText());
                        }
                        case "SubdivisionCode" -> {
                            if (inGeo && currentRrs != null) currentRrs.setGeoSubdivisionCode(r.getElementText());
                        }
                        case "CollectionId" -> {
                            if (inCidr && currentRrs != null) currentRrs.setCidrCollectionId(r.getElementText());
                        }
                        case "LocationName" -> {
                            if (inCidr && currentRrs != null) currentRrs.setCidrLocationName(r.getElementText());
                        }
                        case "AWSRegion" -> {
                            if (inGeoProx && currentRrs != null) currentRrs.setGeoProximityAwsRegion(r.getElementText());
                        }
                        case "LocalZoneGroup" -> {
                            if (inGeoProx && currentRrs != null) {
                                currentRrs.setGeoProximityLocalZoneGroup(r.getElementText());
                            }
                        }
                        case "Bias" -> {
                            if (inGeoProx && currentRrs != null) {
                                try { currentRrs.setGeoProximityBias(Integer.parseInt(r.getElementText())); }
                                catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "ResourceRecords" -> inResourceRecords = false;
                        case "AliasTarget" -> {
                            if (inAlias && currentRrs != null && currentAlias != null) {
                                currentRrs.setAliasTarget(currentAlias);
                            }
                            inAlias = false;
                            currentAlias = null;
                        }
                        case "GeoLocation" -> inGeo = false;
                        case "CidrRoutingConfig" -> inCidr = false;
                        case "GeoProximityLocation" -> inGeoProx = false;
                        case "ResourceRecordSet" -> {
                            if (inRrs && currentRrs != null && currentRecords != null) {
                                if (!currentRecords.isEmpty()) currentRrs.setRecords(currentRecords);
                            }
                            inRrs = false;
                        }
                        case "Change" -> {
                            if (inChange && currentAction != null && currentRrs != null) {
                                Map<String, Object> change = new HashMap<>();
                                change.put("action", currentAction);
                                change.put("rrs", currentRrs);
                                result.add(change);
                            }
                            inChange = false;
                            currentAction = null;
                            currentRrs = null;
                            currentRecords = null;
                        }
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return result;
    }

    private HealthCheckConfig parseHealthCheckConfig(String body) {
        HealthCheckConfig cfg = new HealthCheckConfig();
        cfg.setType(XmlParser.extractFirst(body, "Type", null));
        cfg.setIpAddress(XmlParser.extractFirst(body, "IPAddress", null));
        String portStr = XmlParser.extractFirst(body, "Port", null);
        if (portStr != null) {
            try { cfg.setPort(Integer.parseInt(portStr)); } catch (NumberFormatException ignored) {}
        }
        cfg.setResourcePath(XmlParser.extractFirst(body, "ResourcePath", null));
        cfg.setFullyQualifiedDomainName(XmlParser.extractFirst(body, "FullyQualifiedDomainName", null));
        String riStr = XmlParser.extractFirst(body, "RequestInterval", null);
        if (riStr != null) {
            try { cfg.setRequestInterval(Integer.parseInt(riStr)); } catch (NumberFormatException ignored) {}
        }
        String ftStr = XmlParser.extractFirst(body, "FailureThreshold", null);
        if (ftStr != null) {
            try { cfg.setFailureThreshold(Integer.parseInt(ftStr)); } catch (NumberFormatException ignored) {}
        }
        return cfg;
    }

    /**
     * Parses Key elements that appear inside a RemoveTagKeys block only.
     * Uses StAX to avoid matching Key elements from AddTags.
     */
    private List<String> parseRemoveTagKeys(String body) {
        List<String> keys = new ArrayList<>();
        if (body == null || body.isEmpty()) return keys;
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inRemove = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if ("RemoveTagKeys".equals(r.getLocalName())) {
                        inRemove = true;
                    } else if (inRemove && "Key".equals(r.getLocalName())) {
                        keys.add(r.getElementText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("RemoveTagKeys".equals(r.getLocalName())) inRemove = false;
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return keys;
    }
}
