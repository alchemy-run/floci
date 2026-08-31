package io.github.hectorvent.floci.services.cloudfront;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudfront.edge.CloudFrontFunctionRuntime;
import io.github.hectorvent.floci.services.cloudfront.model.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/2020-05-31")
public class CloudFrontController {

    private static final Logger LOG = Logger.getLogger(CloudFrontController.class);

    private static final String NS = AwsNamespaces.CLOUDFRONT;
    private static final String XML = "application/xml";

    private static final XMLInputFactory XML_FACTORY;

    static {
        XML_FACTORY = XMLInputFactory.newInstance();
        XML_FACTORY.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        XML_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XML_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    private final CloudFrontService service;
    private final CloudFrontFunctionRuntime functionRuntime;
    private final ObjectMapper mapper;

    @Inject
    public CloudFrontController(CloudFrontService service,
                                CloudFrontFunctionRuntime functionRuntime,
                                ObjectMapper mapper) {
        this.service = service;
        this.functionRuntime = functionRuntime;
        this.mapper = mapper;
    }

    // ── Distributions ─────────────────────────────────────────────────────────

    @POST
    @Path("/distribution")
    public Response createDistribution(@QueryParam("WithTags") String withTags, String body) {
        try {
            DistributionConfig config = parseDistributionConfig(body);
            Map<String, String> tags = new LinkedHashMap<>();
            if (withTags != null) {
                tags = parseTags(body);
            }
            Distribution dist = new Distribution();
            dist.setConfig(config);
            dist = service.createDistribution(dist, tags);
            String xml = xmlDistribution(dist);
            return Response.created(URI.create("/2020-05-31/distribution/" + dist.getId()))
                    .type(XML)
                    .header("ETag", dist.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/distribution/{Id}")
    public Response getDistribution(@PathParam("Id") String id) {
        try {
            Distribution dist = service.getDistribution(id);
            String xml = xmlDistribution(dist);
            return Response.ok(xml, XML).header("ETag", dist.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/distribution/{Id}/config")
    public Response getDistributionConfig(@PathParam("Id") String id) {
        try {
            Distribution dist = service.getDistribution(id);
            String xml = new XmlBuilder()
                    .start("DistributionConfig", NS)
                    .raw(xmlDistributionConfigBody(dist.getConfig()))
                    .end("DistributionConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", dist.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/distribution/{Id}/config")
    public Response updateDistribution(@PathParam("Id") String id,
                                       @HeaderParam("If-Match") String ifMatch,
                                       String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            DistributionConfig config = parseDistributionConfig(body);
            Distribution updated = new Distribution();
            updated.setConfig(config);
            updated = service.updateDistribution(id, ifMatch, updated);
            String xml = xmlDistribution(updated);
            return Response.ok(xml, XML).header("ETag", updated.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/distribution/{Id}")
    public Response deleteDistribution(@PathParam("Id") String id,
                                       @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteDistribution(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/distribution")
    public Response listDistributions(@QueryParam("Marker") String marker,
                                      @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            List<Distribution> dists = service.listDistributions(marker, maxItems);
            long total = service.listDistributions(null, Integer.MAX_VALUE).size();
            boolean truncated = dists.size() == maxItems && dists.size() < total;

            XmlBuilder xml = new XmlBuilder()
                    .start("DistributionList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated);
            if (truncated && !dists.isEmpty()) {
                xml.elem("NextMarker", dists.get(dists.size() - 1).getId());
            }
            xml.elem("Quantity", dists.size())
                    .start("Items");
            for (Distribution d : dists) {
                xml.raw(xmlDistributionSummary(d));
            }
            xml.end("Items")
                    .end("DistributionList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/distribution/{TargetDistributionId}/associate-alias")
    public Response associateAlias(@PathParam("TargetDistributionId") String targetId,
                                   @QueryParam("Alias") String alias) {
        try {
            service.associateAlias(targetId, alias);
            return Response.ok("", XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Invalidations ─────────────────────────────────────────────────────────

    @POST
    @Path("/distribution/{Id}/invalidation")
    public Response createInvalidation(@PathParam("Id") String id, String body) {
        try {
            Invalidation inv = parseInvalidation(body);
            inv = service.createInvalidation(id, inv);
            String xml = new XmlBuilder()
                    .start("Invalidation", NS)
                    .raw(xmlInvalidationBody(inv))
                    .end("Invalidation")
                    .build();
            return Response.created(
                            URI.create("/2020-05-31/distribution/" + id + "/invalidation/" + inv.getId()))
                    .type(XML)
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/distribution/{Id}/invalidation/{InvId}")
    public Response getInvalidation(@PathParam("Id") String id,
                                    @PathParam("InvId") String invId) {
        try {
            Invalidation inv = service.getInvalidation(id, invId);
            String xml = new XmlBuilder()
                    .start("Invalidation", NS)
                    .raw(xmlInvalidationBody(inv))
                    .end("Invalidation")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/distribution/{Id}/invalidation")
    public Response listInvalidations(@PathParam("Id") String id,
                                      @QueryParam("Marker") String marker,
                                      @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            List<Invalidation> invs = service.listInvalidations(id, marker, maxItems);
            boolean truncated = invs.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("InvalidationList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", invs.size())
                    .start("Items");
            for (Invalidation inv : invs) {
                xml.start("InvalidationSummary")
                        .elem("Id", inv.getId())
                        .elem("Status", inv.getStatus())
                        .elem("CreateTime", inv.getCreateTime() != null ? inv.getCreateTime().toString() : "")
                        .end("InvalidationSummary");
            }
            xml.end("Items").end("InvalidationList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Cache Policies ────────────────────────────────────────────────────────

    @POST
    @Path("/cache-policy")
    public Response createCachePolicy(String body) {
        try {
            CachePolicy policy = parseCachePolicy(body);
            policy = service.createCachePolicy(policy);
            String xml = xmlCachePolicyResponse(policy);
            return Response.created(URI.create("/2020-05-31/cache-policy/" + policy.getId()))
                    .type(XML)
                    .header("ETag", policy.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/cache-policy/{Id}")
    public Response getCachePolicy(@PathParam("Id") String id) {
        try {
            CachePolicy policy = service.getCachePolicy(id);
            return Response.ok(xmlCachePolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/cache-policy/{Id}/config")
    public Response getCachePolicyConfig(@PathParam("Id") String id) {
        try {
            CachePolicy policy = service.getCachePolicy(id);
            String xml = new XmlBuilder()
                    .start("CachePolicyConfig", NS)
                    .elem("Name", policy.getName())
                    .elem("Comment", policy.getComment() != null ? policy.getComment() : "")
                    .end("CachePolicyConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/cache-policy/{Id}")
    public Response updateCachePolicy(@PathParam("Id") String id,
                                      @HeaderParam("If-Match") String ifMatch,
                                      String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            CachePolicy policy = parseCachePolicy(body);
            policy = service.updateCachePolicy(id, ifMatch, policy);
            return Response.ok(xmlCachePolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/cache-policy/{Id}")
    public Response deleteCachePolicy(@PathParam("Id") String id,
                                      @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteCachePolicy(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/cache-policy")
    public Response listCachePolicies(@QueryParam("Marker") String marker,
                                      @QueryParam("MaxItems") @DefaultValue("100") int maxItems,
                                      @QueryParam("Type") String type) {
        try {
            List<CachePolicy> policies = service.listCachePolicies(marker, maxItems);
            boolean truncated = policies.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("CachePolicyList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", policies.size())
                    .start("Items");
            for (CachePolicy p : policies) {
                xml.start("CachePolicySummary")
                        .elem("Type", "custom")
                        .raw(xmlCachePolicyResponse(p))
                        .end("CachePolicySummary");
            }
            xml.end("Items").end("CachePolicyList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Origin Request Policies ───────────────────────────────────────────────

    @POST
    @Path("/origin-request-policy")
    public Response createOriginRequestPolicy(String body) {
        try {
            OriginRequestPolicy policy = parseOriginRequestPolicy(body);
            policy = service.createOriginRequestPolicy(policy);
            String xml = xmlOriginRequestPolicyResponse(policy);
            return Response.created(URI.create("/2020-05-31/origin-request-policy/" + policy.getId()))
                    .type(XML)
                    .header("ETag", policy.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-request-policy/{Id}")
    public Response getOriginRequestPolicy(@PathParam("Id") String id) {
        try {
            OriginRequestPolicy policy = service.getOriginRequestPolicy(id);
            return Response.ok(xmlOriginRequestPolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-request-policy/{Id}/config")
    public Response getOriginRequestPolicyConfig(@PathParam("Id") String id) {
        try {
            OriginRequestPolicy policy = service.getOriginRequestPolicy(id);
            String xml = new XmlBuilder()
                    .start("OriginRequestPolicyConfig", NS)
                    .elem("Name", policy.getName())
                    .elem("Comment", policy.getComment() != null ? policy.getComment() : "")
                    .end("OriginRequestPolicyConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/origin-request-policy/{Id}")
    public Response updateOriginRequestPolicy(@PathParam("Id") String id,
                                              @HeaderParam("If-Match") String ifMatch,
                                              String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            OriginRequestPolicy policy = parseOriginRequestPolicy(body);
            policy = service.updateOriginRequestPolicy(id, ifMatch, policy);
            return Response.ok(xmlOriginRequestPolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/origin-request-policy/{Id}")
    public Response deleteOriginRequestPolicy(@PathParam("Id") String id,
                                              @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteOriginRequestPolicy(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-request-policy")
    public Response listOriginRequestPolicies(@QueryParam("Marker") String marker,
                                              @QueryParam("MaxItems") @DefaultValue("100") int maxItems,
                                              @QueryParam("Type") String type) {
        try {
            List<OriginRequestPolicy> policies = service.listOriginRequestPolicies(marker, maxItems);
            boolean truncated = policies.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("OriginRequestPolicyList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", policies.size())
                    .start("Items");
            for (OriginRequestPolicy p : policies) {
                xml.start("OriginRequestPolicySummary")
                        .elem("Type", "custom")
                        .raw(xmlOriginRequestPolicyResponse(p))
                        .end("OriginRequestPolicySummary");
            }
            xml.end("Items").end("OriginRequestPolicyList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Response Headers Policies ─────────────────────────────────────────────

    @POST
    @Path("/response-headers-policy")
    public Response createResponseHeadersPolicy(String body) {
        try {
            ResponseHeadersPolicy policy = parseResponseHeadersPolicy(body);
            policy = service.createResponseHeadersPolicy(policy);
            String xml = xmlResponseHeadersPolicyResponse(policy);
            return Response.created(
                            URI.create("/2020-05-31/response-headers-policy/" + policy.getId()))
                    .type(XML)
                    .header("ETag", policy.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/response-headers-policy/{Id}")
    public Response getResponseHeadersPolicy(@PathParam("Id") String id) {
        try {
            ResponseHeadersPolicy policy = service.getResponseHeadersPolicy(id);
            return Response.ok(xmlResponseHeadersPolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/response-headers-policy/{Id}/config")
    public Response getResponseHeadersPolicyConfig(@PathParam("Id") String id) {
        try {
            ResponseHeadersPolicy policy = service.getResponseHeadersPolicy(id);
            String xml = new XmlBuilder()
                    .start("ResponseHeadersPolicyConfig", NS)
                    .elem("Name", policy.getName())
                    .elem("Comment", policy.getComment() != null ? policy.getComment() : "")
                    .end("ResponseHeadersPolicyConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/response-headers-policy/{Id}")
    public Response updateResponseHeadersPolicy(@PathParam("Id") String id,
                                                @HeaderParam("If-Match") String ifMatch,
                                                String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            ResponseHeadersPolicy policy = parseResponseHeadersPolicy(body);
            policy = service.updateResponseHeadersPolicy(id, ifMatch, policy);
            return Response.ok(xmlResponseHeadersPolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/response-headers-policy/{Id}")
    public Response deleteResponseHeadersPolicy(@PathParam("Id") String id,
                                                @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteResponseHeadersPolicy(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/response-headers-policy")
    public Response listResponseHeadersPolicies(@QueryParam("Marker") String marker,
                                                @QueryParam("MaxItems") @DefaultValue("100") int maxItems,
                                                @QueryParam("Type") String type) {
        try {
            List<ResponseHeadersPolicy> policies = service.listResponseHeadersPolicies(marker, maxItems);
            boolean truncated = policies.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("ResponseHeadersPolicyList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", policies.size())
                    .start("Items");
            for (ResponseHeadersPolicy p : policies) {
                xml.start("ResponseHeadersPolicySummary")
                        .elem("Type", "custom")
                        .raw(xmlResponseHeadersPolicyResponse(p))
                        .end("ResponseHeadersPolicySummary");
            }
            xml.end("Items").end("ResponseHeadersPolicyList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Origin Access Control ─────────────────────────────────────────────────

    @POST
    @Path("/origin-access-control")
    public Response createOriginAccessControl(String body) {
        try {
            OriginAccessControl oac = parseOriginAccessControl(body);
            oac = service.createOriginAccessControl(oac);
            String xml = xmlOriginAccessControlResponse(oac);
            return Response.created(URI.create("/2020-05-31/origin-access-control/" + oac.getId()))
                    .type(XML)
                    .header("ETag", oac.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-access-control/{Id}")
    public Response getOriginAccessControl(@PathParam("Id") String id) {
        try {
            OriginAccessControl oac = service.getOriginAccessControl(id);
            return Response.ok(xmlOriginAccessControlResponse(oac), XML)
                    .header("ETag", oac.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-access-control/{Id}/config")
    public Response getOriginAccessControlConfig(@PathParam("Id") String id) {
        try {
            OriginAccessControl oac = service.getOriginAccessControl(id);
            String xml = new XmlBuilder()
                    .start("OriginAccessControlConfig", NS)
                    .elem("Name", oac.getName())
                    .elem("Description", oac.getDescription() != null ? oac.getDescription() : "")
                    .elem("SigningProtocol", oac.getSigningProtocol())
                    .elem("SigningBehavior", oac.getSigningBehavior())
                    .elem("OriginAccessControlOriginType", oac.getOriginAccessControlOriginType())
                    .end("OriginAccessControlConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", oac.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/origin-access-control/{Id}")
    public Response updateOriginAccessControl(@PathParam("Id") String id,
                                              @HeaderParam("If-Match") String ifMatch,
                                              String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            OriginAccessControl oac = parseOriginAccessControl(body);
            oac = service.updateOriginAccessControl(id, ifMatch, oac);
            return Response.ok(xmlOriginAccessControlResponse(oac), XML)
                    .header("ETag", oac.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/origin-access-control/{Id}")
    public Response deleteOriginAccessControl(@PathParam("Id") String id,
                                              @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteOriginAccessControl(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-access-control")
    public Response listOriginAccessControls(@QueryParam("Marker") String marker,
                                             @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            List<OriginAccessControl> oacs = service.listOriginAccessControls(marker, maxItems);
            boolean truncated = oacs.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("OriginAccessControlList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", oacs.size())
                    .start("Items");
            for (OriginAccessControl o : oacs) {
                xml.raw(xmlOriginAccessControlSummary(o));
            }
            xml.end("Items").end("OriginAccessControlList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Origin Access Identity ────────────────────────────────────────────────

    @POST
    @Path("/origin-access-identity/cloudfront")
    public Response createCloudFrontOriginAccessIdentity(String body) {
        try {
            CloudFrontOriginAccessIdentity oai = parseOai(body);
            oai = service.createCloudFrontOriginAccessIdentity(oai);
            String xml = xmlOaiResponse(oai);
            return Response.created(
                            URI.create("/2020-05-31/origin-access-identity/cloudfront/" + oai.getId()))
                    .type(XML)
                    .header("ETag", oai.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-access-identity/cloudfront/{Id}")
    public Response getCloudFrontOriginAccessIdentity(@PathParam("Id") String id) {
        try {
            CloudFrontOriginAccessIdentity oai = service.getCloudFrontOriginAccessIdentity(id);
            return Response.ok(xmlOaiResponse(oai), XML).header("ETag", oai.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-access-identity/cloudfront/{Id}/config")
    public Response getCloudFrontOriginAccessIdentityConfig(@PathParam("Id") String id) {
        try {
            CloudFrontOriginAccessIdentity oai = service.getCloudFrontOriginAccessIdentity(id);
            String xml = new XmlBuilder()
                    .start("CloudFrontOriginAccessIdentityConfig", NS)
                    .elem("CallerReference", oai.getCallerReference())
                    .elem("Comment", oai.getComment() != null ? oai.getComment() : "")
                    .end("CloudFrontOriginAccessIdentityConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", oai.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/origin-access-identity/cloudfront/{Id}/config")
    public Response updateCloudFrontOriginAccessIdentity(@PathParam("Id") String id,
                                                         @HeaderParam("If-Match") String ifMatch,
                                                         String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            CloudFrontOriginAccessIdentity oai = parseOai(body);
            oai = service.updateCloudFrontOriginAccessIdentity(id, ifMatch, oai);
            return Response.ok(xmlOaiResponse(oai), XML).header("ETag", oai.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/origin-access-identity/cloudfront/{Id}")
    public Response deleteCloudFrontOriginAccessIdentity(@PathParam("Id") String id,
                                                         @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteCloudFrontOriginAccessIdentity(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-access-identity/cloudfront")
    public Response listCloudFrontOriginAccessIdentities(
            @QueryParam("Marker") String marker,
            @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            List<CloudFrontOriginAccessIdentity> oais =
                    service.listCloudFrontOriginAccessIdentities(marker, maxItems);
            boolean truncated = oais.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("ListCloudFrontOriginAccessIdentitiesResult", NS)
                    .start("CloudFrontOriginAccessIdentityList")
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", oais.size())
                    .start("Items");
            for (CloudFrontOriginAccessIdentity o : oais) {
                xml.start("CloudFrontOriginAccessIdentitySummary")
                        .elem("Id", o.getId())
                        .elem("S3CanonicalUserId", o.getS3CanonicalUserId())
                        .elem("Comment", o.getComment() != null ? o.getComment() : "")
                        .end("CloudFrontOriginAccessIdentitySummary");
            }
            xml.end("Items").end("CloudFrontOriginAccessIdentityList")
                    .end("ListCloudFrontOriginAccessIdentitiesResult");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── CloudFront Functions ──────────────────────────────────────────────────

    @POST
    @Path("/function")
    public Response createFunction(String body) {
        try {
            CloudFrontFunction fn = parseFunction(body);
            fn = service.createFunction(fn);
            String xml = xmlFunctionResponse(fn);
            return Response.created(URI.create("/2020-05-31/function/" + fn.getName()))
                    .type(XML)
                    .header("ETag", fn.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/function/{Name}")
    public Response getFunction(@PathParam("Name") String name,
                                @QueryParam("Stage") String stage) {
        try {
            CloudFrontFunction fn = service.describeFunction(name, stage);
            return Response.ok(xmlFunctionResponse(fn), XML).header("ETag", fn.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/function/{Name}/describe")
    public Response describeFunction(@PathParam("Name") String name,
                                     @QueryParam("Stage") String stage) {
        try {
            CloudFrontFunction fn = service.describeFunction(name, stage);
            return Response.ok(xmlFunctionResponse(fn), XML).header("ETag", fn.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/function/{Name}")
    public Response updateFunction(@PathParam("Name") String name,
                                   @HeaderParam("If-Match") String ifMatch,
                                   String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            CloudFrontFunction fn = parseFunction(body);
            fn = service.updateFunction(name, ifMatch, fn);
            return Response.ok(xmlFunctionResponse(fn), XML).header("ETag", fn.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/function/{Name}/publish")
    public Response publishFunction(@PathParam("Name") String name,
                                    @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            CloudFrontFunction fn = service.publishFunction(name, ifMatch);
            return Response.ok(xmlFunctionResponse(fn), XML).header("ETag", fn.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * Runs a function against a caller-supplied event object and returns the
     * result, its logs and its error message — the same runtime the emulated
     * edge uses, so a local {@code test-function} result and a locally-served
     * request agree.
     */
    @POST
    @Path("/function/{Name}/test")
    public Response testFunction(@PathParam("Name") String name,
                                 @HeaderParam("If-Match") String ifMatch,
                                 String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            String stage = XmlParser.extractFirst(body, "Stage", "DEVELOPMENT");
            CloudFrontFunction fn = service.describeFunction(name, stage);
            if (!ifMatch.equals(fn.getEtag())) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            JsonNode event = parseEventObject(XmlParser.extractFirst(body, "EventObject", null));
            CloudFrontFunctionRuntime.Execution execution = functionRuntime.execute(fn, event);

            XmlBuilder xml = new XmlBuilder()
                    .start("TestResult", NS)
                    .raw(xmlFunctionResponse(fn))
                    .elem("ComputeUtilization", execution.computeUtilization())
                    .start("FunctionExecutionLogs");
            for (String log : execution.logs()) {
                xml.elem("member", log);
            }
            xml.end("FunctionExecutionLogs");
            if (execution.ok()) {
                xml.elem("FunctionOutput", functionOutput(execution));
            } else {
                xml.elem("FunctionErrorMessage", execution.error());
            }
            xml.end("TestResult");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /** The {@code EventObject} blob is base64 in the REST-XML wire format. */
    private JsonNode parseEventObject(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new AwsException("InvalidArgument", "EventObject is required.", 400);
        }
        try {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(encoded.replaceAll("\\s", ""));
            } catch (IllegalArgumentException notBase64) {
                decoded = encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            return mapper.readTree(decoded);
        } catch (Exception e) {
            throw new AwsException("InvalidArgument",
                    "EventObject is not a valid JSON event object: " + e.getMessage(), 400);
        }
    }

    /** CloudFront returns the result wrapped as {@code {"request":…}} or {@code {"response":…}}. */
    private String functionOutput(CloudFrontFunctionRuntime.Execution execution) {
        try {
            var wrapper = mapper.createObjectNode();
            wrapper.set(execution.isResponse() ? "response" : "request", execution.output());
            return mapper.writeValueAsString(wrapper);
        } catch (Exception e) {
            throw new AwsException("InvalidArgument",
                    "Could not serialize the function output: " + e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/function/{Name}")
    public Response deleteFunction(@PathParam("Name") String name,
                                   @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteFunction(name, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/function")
    public Response listFunctions(@QueryParam("Stage") String stage,
                                  @QueryParam("Marker") String marker,
                                  @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            List<CloudFrontFunction> fns = service.listFunctions(stage);
            XmlBuilder xml = new XmlBuilder()
                    .start("FunctionList", NS)
                    .elem("MaxItems", maxItems)
                    .elem("Quantity", fns.size())
                    .start("Items");
            for (CloudFrontFunction fn : fns) {
                xml.start("FunctionSummary")
                        .elem("Name", fn.getName())
                        .elem("Status", fn.getStatus())
                        .start("FunctionConfig")
                        .elem("Comment", fn.getComment() != null ? fn.getComment() : "")
                        .elem("Runtime", fn.getRuntime() != null ? fn.getRuntime() : "cloudfront-js-2.0")
                        .raw(xmlKeyValueStoreAssociations(fn.getKeyValueStoreArns()))
                        .end("FunctionConfig")
                        .start("FunctionMetadata")
                        .elem("FunctionARN", AwsArnUtils.Arn.of("cloudfront", "", service.getAccountId(),
                                "function/" + fn.getName()).toString())
                        .elem("Stage", fn.getStage())
                        .elem("CreatedTime", fn.getCreatedTime() != null ? fn.getCreatedTime().toString() : "")
                        .elem("LastModifiedTime",
                                fn.getLastModifiedTime() != null ? fn.getLastModifiedTime().toString() : "")
                        .end("FunctionMetadata")
                        .end("FunctionSummary");
            }
            xml.end("Items").end("FunctionList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Tagging ───────────────────────────────────────────────────────────────

    @GET
    @Path("/tagging")
    public Response listTagsForResource(@QueryParam("Resource") String resource) {
        try {
            Map<String, String> tags = service.listTagsForResource(resource);
            XmlBuilder xml = new XmlBuilder()
                    .start("ListTagsForResourceResult", NS)
                    .start("Tags")
                    .start("Items");
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                xml.start("Tag")
                        .elem("Key", entry.getKey())
                        .elem("Value", entry.getValue())
                        .end("Tag");
            }
            xml.end("Items").end("Tags").end("ListTagsForResourceResult");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/tagging")
    public Response tagging(@QueryParam("Operation") String operation,
                            @QueryParam("Resource") String resource,
                            String body) {
        try {
            if ("Tag".equals(operation)) {
                Map<String, String> tags = parseTags(body);
                service.tagResource(resource, tags);
            } else if ("Untag".equals(operation)) {
                List<String> keys = XmlParser.extractAll(body, "Key");
                service.untagResource(resource, keys);
            } else {
                throw new AwsException("InvalidArgument", "Unknown tagging operation.", 400);
            }
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Continuous Deployment Policies ───────────────────────────────────────

    @POST
    @Path("/continuous-deployment-policy")
    public Response createContinuousDeploymentPolicy(String body) {
        try {
            ContinuousDeploymentPolicy policy = parseContinuousDeploymentPolicy(body);
            policy = service.createContinuousDeploymentPolicy(policy);
            String xml = xmlContinuousDeploymentPolicyResponse(policy);
            return Response.created(URI.create("/2020-05-31/continuous-deployment-policy/" + policy.getId()))
                    .type(XML)
                    .header("ETag", policy.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/continuous-deployment-policy/{Id}")
    public Response getContinuousDeploymentPolicy(@PathParam("Id") String id) {
        try {
            ContinuousDeploymentPolicy policy = service.getContinuousDeploymentPolicy(id);
            return Response.ok(xmlContinuousDeploymentPolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/continuous-deployment-policy/{Id}")
    public Response updateContinuousDeploymentPolicy(@PathParam("Id") String id,
                                                      @HeaderParam("If-Match") String ifMatch,
                                                      String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            ContinuousDeploymentPolicy policy = parseContinuousDeploymentPolicy(body);
            policy = service.updateContinuousDeploymentPolicy(id, ifMatch, policy);
            return Response.ok(xmlContinuousDeploymentPolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/continuous-deployment-policy/{Id}")
    public Response deleteContinuousDeploymentPolicy(@PathParam("Id") String id,
                                                      @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteContinuousDeploymentPolicy(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/continuous-deployment-policy")
    public Response listContinuousDeploymentPolicies(@QueryParam("Marker") String marker,
                                                      @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            List<ContinuousDeploymentPolicy> policies =
                    service.listContinuousDeploymentPolicies(marker, maxItems);
            boolean truncated = policies.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("ListContinuousDeploymentPoliciesResult", NS)
                    .start("ContinuousDeploymentPolicyList")
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", policies.size())
                    .start("Items");
            for (ContinuousDeploymentPolicy p : policies) {
                xml.start("ContinuousDeploymentPolicySummary")
                        .elem("Type", "custom")
                        .raw(xmlContinuousDeploymentPolicyResponse(p))
                        .end("ContinuousDeploymentPolicySummary");
            }
            xml.end("Items").end("ContinuousDeploymentPolicyList")
                    .end("ListContinuousDeploymentPoliciesResult");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── CopyDistribution ──────────────────────────────────────────────────────

    @POST
    @Path("/distribution/{PrimaryDistributionId}/copy")
    public Response copyDistribution(@PathParam("PrimaryDistributionId") String primaryId,
                                     String body) {
        try {
            String callerReference = XmlParser.extractFirst(body, "CallerReference", null);
            Map<String, String> tags = parseTags(body);
            Distribution dist = service.copyDistribution(primaryId, callerReference, tags);
            String xml = xmlDistribution(dist);
            return Response.created(URI.create("/2020-05-31/distribution/" + dist.getId()))
                    .type(XML)
                    .header("ETag", dist.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Public Keys ───────────────────────────────────────────────────────────

    @POST
    @Path("/public-key")
    public Response createPublicKey(String body) {
        try {
            PublicKey key = parsePublicKey(body);
            key = service.createPublicKey(key);
            String xml = xmlPublicKeyResponse(key);
            return Response.created(URI.create("/2020-05-31/public-key/" + key.getId()))
                    .type(XML)
                    .header("ETag", key.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/public-key/{Id}")
    public Response getPublicKey(@PathParam("Id") String id) {
        try {
            PublicKey key = service.getPublicKey(id);
            return Response.ok(xmlPublicKeyResponse(key), XML).header("ETag", key.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/public-key/{Id}/config")
    public Response getPublicKeyConfig(@PathParam("Id") String id) {
        try {
            PublicKey key = service.getPublicKey(id);
            String xml = new XmlBuilder()
                    .start("PublicKeyConfig", NS)
                    .elem("CallerReference", key.getCallerReference() != null ? key.getCallerReference() : "")
                    .elem("Name", key.getName() != null ? key.getName() : "")
                    .elem("EncodedKey", key.getEncodedKey() != null ? key.getEncodedKey() : "")
                    .elem("Comment", key.getComment() != null ? key.getComment() : "")
                    .end("PublicKeyConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", key.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/public-key/{Id}/config")
    public Response updatePublicKey(@PathParam("Id") String id,
                                    @HeaderParam("If-Match") String ifMatch,
                                    String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            PublicKey key = parsePublicKey(body);
            key = service.updatePublicKey(id, ifMatch, key);
            return Response.ok(xmlPublicKeyResponse(key), XML).header("ETag", key.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/public-key/{Id}")
    public Response deletePublicKey(@PathParam("Id") String id,
                                    @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deletePublicKey(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/public-key")
    public Response listPublicKeys(@QueryParam("Marker") String marker,
                                   @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            List<PublicKey> keys = service.listPublicKeys(marker, maxItems);
            boolean truncated = keys.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("PublicKeyList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", keys.size())
                    .start("Items");
            for (PublicKey k : keys) {
                xml.raw(xmlPublicKeySummary(k));
            }
            xml.end("Items").end("PublicKeyList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Key Groups ────────────────────────────────────────────────────────────

    @POST
    @Path("/key-group")
    public Response createKeyGroup(String body) {
        try {
            KeyGroup group = parseKeyGroup(body);
            group = service.createKeyGroup(group);
            String xml = xmlKeyGroupResponse(group);
            return Response.created(URI.create("/2020-05-31/key-group/" + group.getId()))
                    .type(XML)
                    .header("ETag", group.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/key-group/{Id}")
    public Response getKeyGroup(@PathParam("Id") String id) {
        try {
            KeyGroup group = service.getKeyGroup(id);
            return Response.ok(xmlKeyGroupResponse(group), XML).header("ETag", group.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/key-group/{Id}/config")
    public Response getKeyGroupConfig(@PathParam("Id") String id) {
        try {
            KeyGroup group = service.getKeyGroup(id);
            List<String> items = group.getItems() != null ? group.getItems() : List.of();
            String xml = new XmlBuilder()
                    .start("KeyGroupConfig", NS)
                    .elem("Name", group.getName() != null ? group.getName() : "")
                    .elem("Comment", group.getComment() != null ? group.getComment() : "")
                    .raw(xmlPublicKeyItems(items))
                    .end("KeyGroupConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", group.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/key-group/{Id}")
    public Response updateKeyGroup(@PathParam("Id") String id,
                                   @HeaderParam("If-Match") String ifMatch,
                                   String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            KeyGroup group = parseKeyGroup(body);
            group = service.updateKeyGroup(id, ifMatch, group);
            return Response.ok(xmlKeyGroupResponse(group), XML).header("ETag", group.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/key-group/{Id}")
    public Response deleteKeyGroup(@PathParam("Id") String id,
                                   @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteKeyGroup(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/key-group")
    public Response listKeyGroups(@QueryParam("Marker") String marker,
                                  @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            List<KeyGroup> groups = service.listKeyGroups(marker, maxItems);
            boolean truncated = groups.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("KeyGroupList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", groups.size())
                    .start("Items");
            for (KeyGroup g : groups) {
                xml.start("KeyGroupSummary").raw(xmlKeyGroupResponse(g)).end("KeyGroupSummary");
            }
            xml.end("Items").end("KeyGroupList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Realtime Log Configs ──────────────────────────────────────────────────

    @POST
    @Path("/realtime-log-config")
    public Response createRealtimeLogConfig(String body) {
        try {
            RealtimeLogConfig cfg = parseRealtimeLogConfig(body);
            cfg = service.createRealtimeLogConfig(cfg);
            String xml = new XmlBuilder()
                    .start("CreateRealtimeLogConfigResult", NS)
                    .raw(xmlRealtimeLogConfigBody(cfg))
                    .end("CreateRealtimeLogConfigResult")
                    .build();
            return Response.created(URI.create("/2020-05-31/realtime-log-config"))
                    .type(XML)
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/get-realtime-log-config")
    public Response getRealtimeLogConfig(String body) {
        try {
            String name = XmlParser.extractFirst(body, "Name", null);
            String arn = XmlParser.extractFirst(body, "ARN", null);
            RealtimeLogConfig cfg = service.getRealtimeLogConfig(name != null ? name : arn);
            String xml = new XmlBuilder()
                    .start("GetRealtimeLogConfigResult", NS)
                    .raw(xmlRealtimeLogConfigBody(cfg))
                    .end("GetRealtimeLogConfigResult")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/realtime-log-config")
    public Response updateRealtimeLogConfig(String body) {
        try {
            RealtimeLogConfig cfg = parseRealtimeLogConfig(body);
            cfg = service.updateRealtimeLogConfig(cfg);
            String xml = new XmlBuilder()
                    .start("UpdateRealtimeLogConfigResult", NS)
                    .raw(xmlRealtimeLogConfigBody(cfg))
                    .end("UpdateRealtimeLogConfigResult")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/delete-realtime-log-config")
    public Response deleteRealtimeLogConfig(String body) {
        try {
            String name = XmlParser.extractFirst(body, "Name", null);
            String arn = XmlParser.extractFirst(body, "ARN", null);
            service.deleteRealtimeLogConfig(name != null ? name : arn);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/realtime-log-config")
    public Response listRealtimeLogConfigs(@QueryParam("Marker") String marker,
                                           @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            List<RealtimeLogConfig> configs = service.listRealtimeLogConfigs(marker, maxItems);
            boolean truncated = configs.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("RealtimeLogConfigs", NS)
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", configs.size())
                    .start("Items");
            for (RealtimeLogConfig c : configs) {
                xml.raw(xmlRealtimeLogConfigBody(c));
            }
            xml.end("Items").end("RealtimeLogConfigs");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Streaming Distributions ───────────────────────────────────────────────

    @POST
    @Path("/streaming-distribution")
    public Response createStreamingDistribution(String body) {
        try {
            StreamingDistribution sd = parseStreamingDistribution(body);
            sd = service.createStreamingDistribution(sd);
            String xml = xmlStreamingDistributionResponse(sd);
            return Response.created(URI.create("/2020-05-31/streaming-distribution/" + sd.getId()))
                    .type(XML)
                    .header("ETag", sd.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/streaming-distribution/{Id}")
    public Response getStreamingDistribution(@PathParam("Id") String id) {
        try {
            StreamingDistribution sd = service.getStreamingDistribution(id);
            return Response.ok(xmlStreamingDistributionResponse(sd), XML)
                    .header("ETag", sd.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/streaming-distribution/{Id}/config")
    public Response getStreamingDistributionConfig(@PathParam("Id") String id) {
        try {
            StreamingDistribution sd = service.getStreamingDistribution(id);
            String xml = xmlStreamingDistributionConfigBody(sd);
            return Response.ok(xml, XML).header("ETag", sd.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/streaming-distribution/{Id}/config")
    public Response updateStreamingDistribution(@PathParam("Id") String id,
                                                 @HeaderParam("If-Match") String ifMatch,
                                                 String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            StreamingDistribution sd = parseStreamingDistribution(body);
            sd = service.updateStreamingDistribution(id, ifMatch, sd);
            return Response.ok(xmlStreamingDistributionResponse(sd), XML)
                    .header("ETag", sd.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/streaming-distribution/{Id}")
    public Response deleteStreamingDistribution(@PathParam("Id") String id,
                                                 @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteStreamingDistribution(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Field-Level Encryption Configs ────────────────────────────────────────

    @POST
    @Path("/field-level-encryption")
    public Response createFieldLevelEncryptionConfig(String body) {
        try {
            FieldLevelEncryptionConfig cfg = parseFieldLevelEncryptionConfig(body);
            cfg = service.createFieldLevelEncryptionConfig(cfg);
            String xml = xmlFieldLevelEncryptionConfigResponse(cfg);
            return Response.created(URI.create("/2020-05-31/field-level-encryption/" + cfg.getId()))
                    .type(XML)
                    .header("ETag", cfg.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/field-level-encryption/{Id}/config")
    public Response getFieldLevelEncryptionConfig(@PathParam("Id") String id) {
        try {
            FieldLevelEncryptionConfig cfg = service.getFieldLevelEncryptionConfig(id);
            return Response.ok(xmlFieldLevelEncryptionConfigResponse(cfg), XML)
                    .header("ETag", cfg.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/field-level-encryption/{Id}/config")
    public Response updateFieldLevelEncryptionConfig(@PathParam("Id") String id,
                                                      @HeaderParam("If-Match") String ifMatch,
                                                      String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            FieldLevelEncryptionConfig cfg = parseFieldLevelEncryptionConfig(body);
            cfg = service.updateFieldLevelEncryptionConfig(id, ifMatch, cfg);
            return Response.ok(xmlFieldLevelEncryptionConfigResponse(cfg), XML)
                    .header("ETag", cfg.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/field-level-encryption/{Id}")
    public Response deleteFieldLevelEncryptionConfig(@PathParam("Id") String id,
                                                      @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteFieldLevelEncryptionConfig(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/field-level-encryption")
    public Response listFieldLevelEncryptionConfigs(@QueryParam("Marker") String marker,
                                                     @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            List<FieldLevelEncryptionConfig> configs =
                    service.listFieldLevelEncryptionConfigs(marker, maxItems);
            boolean truncated = configs.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("ListFieldLevelEncryptionConfigsResult", NS)
                    .start("FieldLevelEncryptionList")
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", configs.size())
                    .start("Items");
            for (FieldLevelEncryptionConfig c : configs) {
                xml.raw(xmlFieldLevelEncryptionConfigResponse(c));
            }
            xml.end("Items").end("FieldLevelEncryptionList").end("ListFieldLevelEncryptionConfigsResult");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Field-Level Encryption Profiles ──────────────────────────────────────

    @POST
    @Path("/field-level-encryption-profile")
    public Response createFieldLevelEncryptionProfile(String body) {
        try {
            FieldLevelEncryptionProfile profile = parseFieldLevelEncryptionProfile(body);
            profile = service.createFieldLevelEncryptionProfile(profile);
            String xml = xmlFieldLevelEncryptionProfileResponse(profile);
            return Response.created(
                            URI.create("/2020-05-31/field-level-encryption-profile/" + profile.getId()))
                    .type(XML)
                    .header("ETag", profile.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/field-level-encryption-profile/{Id}")
    public Response getFieldLevelEncryptionProfile(@PathParam("Id") String id) {
        try {
            FieldLevelEncryptionProfile profile = service.getFieldLevelEncryptionProfile(id);
            return Response.ok(xmlFieldLevelEncryptionProfileResponse(profile), XML)
                    .header("ETag", profile.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/field-level-encryption-profile/{Id}/config")
    public Response updateFieldLevelEncryptionProfile(@PathParam("Id") String id,
                                                       @HeaderParam("If-Match") String ifMatch,
                                                       String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            FieldLevelEncryptionProfile profile = parseFieldLevelEncryptionProfile(body);
            profile = service.updateFieldLevelEncryptionProfile(id, ifMatch, profile);
            return Response.ok(xmlFieldLevelEncryptionProfileResponse(profile), XML)
                    .header("ETag", profile.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/field-level-encryption-profile/{Id}")
    public Response deleteFieldLevelEncryptionProfile(@PathParam("Id") String id,
                                                       @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteFieldLevelEncryptionProfile(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/field-level-encryption-profile")
    public Response listFieldLevelEncryptionProfiles(@QueryParam("Marker") String marker,
                                                      @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            List<FieldLevelEncryptionProfile> profiles =
                    service.listFieldLevelEncryptionProfiles(marker, maxItems);
            boolean truncated = profiles.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("ListFieldLevelEncryptionProfilesResult", NS)
                    .start("FieldLevelEncryptionProfileList")
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", profiles.size())
                    .start("Items");
            for (FieldLevelEncryptionProfile p : profiles) {
                xml.raw(xmlFieldLevelEncryptionProfileResponse(p));
            }
            xml.end("Items").end("FieldLevelEncryptionProfileList")
                    .end("ListFieldLevelEncryptionProfilesResult");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Monitoring Subscriptions ──────────────────────────────────────────────

    @POST
    @Path("/distributions/{DistributionId}/monitoring-subscription")
    public Response createMonitoringSubscription(@PathParam("DistributionId") String distributionId,
                                                  String body) {
        try {
            MonitoringSubscription sub = parseMonitoringSubscription(body);
            sub = service.createMonitoringSubscription(distributionId, sub);
            String xml = xmlMonitoringSubscriptionResponse(sub);
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/distributions/{DistributionId}/monitoring-subscription")
    public Response getMonitoringSubscription(@PathParam("DistributionId") String distributionId) {
        try {
            MonitoringSubscription sub = service.getMonitoringSubscription(distributionId);
            return Response.ok(xmlMonitoringSubscriptionResponse(sub), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/distributions/{DistributionId}/monitoring-subscription")
    public Response deleteMonitoringSubscription(@PathParam("DistributionId") String distributionId) {
        try {
            service.deleteMonitoringSubscription(distributionId);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── VPC Origins ───────────────────────────────────────────────────────────

    @POST
    @Path("/vpc-origin")
    public Response createVpcOrigin(String body) {
        try {
            VpcOrigin origin = parseVpcOrigin(body);
            Map<String, String> tags = parseTags(body);
            origin = service.createVpcOrigin(origin, tags);
            return Response.created(URI.create("/2020-05-31/vpc-origin/" + origin.getId()))
                    .type(XML)
                    .header("ETag", origin.getEtag())
                    .header("Location", "/2020-05-31/vpc-origin/" + origin.getId())
                    .entity(xmlVpcOrigin(origin))
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/vpc-origin/{Id}")
    public Response getVpcOrigin(@PathParam("Id") String id) {
        try {
            VpcOrigin origin = service.getVpcOrigin(id);
            return Response.ok(xmlVpcOrigin(origin), XML).header("ETag", origin.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/vpc-origin/{Id}")
    public Response updateVpcOrigin(@PathParam("Id") String id,
                                    @HeaderParam("If-Match") String ifMatch,
                                    String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            VpcOrigin origin = parseVpcOrigin(body);
            origin = service.updateVpcOrigin(id, ifMatch, origin);
            return Response.ok(xmlVpcOrigin(origin), XML).header("ETag", origin.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/vpc-origin/{Id}")
    public Response deleteVpcOrigin(@PathParam("Id") String id,
                                    @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteVpcOrigin(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/vpc-origin")
    public Response listVpcOrigins(@QueryParam("Marker") String marker,
                                   @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            List<VpcOrigin> origins = service.listVpcOrigins(marker, maxItems);
            boolean truncated = origins.size() == maxItems;
            XmlBuilder xml = new XmlBuilder()
                    .start("VpcOriginList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", origins.size())
                    .start("Items");
            for (VpcOrigin o : origins) {
                xml.raw(xmlVpcOriginSummary(o));
            }
            xml.end("Items").end("VpcOriginList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Key Value Stores ──────────────────────────────────────────────────────

    @POST
    @Path("/key-value-store")
    public Response createKeyValueStore(String body) {
        try {
            KeyValueStore store = parseKeyValueStore(body);
            Map<String, String> tags = parseTags(body);
            store = service.createKeyValueStore(store, tags);
            return Response.created(URI.create("/2020-05-31/key-value-store/" + store.getName()))
                    .type(XML)
                    .header("ETag", store.getEtag())
                    .header("Location", "/2020-05-31/key-value-store/" + store.getName())
                    .entity(xmlKeyValueStore(store))
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/key-value-store/{Name}")
    public Response describeKeyValueStore(@PathParam("Name") String name) {
        try {
            KeyValueStore store = service.describeKeyValueStore(name);
            return Response.ok(xmlKeyValueStore(store), XML).header("ETag", store.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/key-value-store/{Name}")
    public Response updateKeyValueStore(@PathParam("Name") String name,
                                        @HeaderParam("If-Match") String ifMatch,
                                        String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            String comment = XmlParser.extractFirst(body, "Comment", "");
            KeyValueStore store = service.updateKeyValueStore(name, ifMatch, comment);
            return Response.ok(xmlKeyValueStore(store), XML).header("ETag", store.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/key-value-store/{Name}")
    public Response deleteKeyValueStore(@PathParam("Name") String name,
                                        @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteKeyValueStore(name, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/key-value-store")
    public Response listKeyValueStores(@QueryParam("Marker") String marker,
                                       @QueryParam("MaxItems") @DefaultValue("100") int maxItems,
                                       @QueryParam("Status") String status) {
        try {
            List<KeyValueStore> stores = service.listKeyValueStores(marker, maxItems, status);
            XmlBuilder xml = new XmlBuilder()
                    .start("KeyValueStoreList", NS)
                    .elem("MaxItems", maxItems)
                    .elem("Quantity", stores.size())
                    .start("Items");
            for (KeyValueStore store : stores) {
                xml.raw(xmlKeyValueStore(store));
            }
            xml.end("Items").end("KeyValueStoreList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── XML builders ──────────────────────────────────────────────────────────

    private String xmlDistribution(Distribution dist) {
        return new XmlBuilder()
                .start("Distribution", NS)
                .elem("Id", dist.getId())
                .elem("ARN", dist.getArn())
                .elem("Status", dist.getStatus())
                .elem("DomainName", dist.getDomainName())
                .elem("LastModifiedTime",
                        dist.getLastModifiedTime() != null ? dist.getLastModifiedTime().toString() : "")
                .start("DistributionConfig")
                .raw(xmlDistributionConfigBody(dist.getConfig()))
                .end("DistributionConfig")
                .end("Distribution")
                .build();
    }

    private String xmlDistributionConfigBody(DistributionConfig cfg) {
        if (cfg == null) {
            return "";
        }
        XmlBuilder xml = new XmlBuilder()
                .elem("CallerReference", cfg.getCallerReference() != null ? cfg.getCallerReference() : "")
                .elem("Enabled", cfg.isEnabled())
                .elem("Comment", cfg.getComment() != null ? cfg.getComment() : "")
                .elem("DefaultRootObject", cfg.getDefaultRootObject() != null ? cfg.getDefaultRootObject() : "")
                .elem("HttpVersion", cfg.getHttpVersion() != null ? cfg.getHttpVersion() : "http2")
                .elem("PriceClass", cfg.getPriceClass() != null ? cfg.getPriceClass() : "PriceClass_All")
                .elem("IsIPV6Enabled", cfg.isIPV6Enabled())
                .elem("WebAclId", cfg.getWebAclId() != null ? cfg.getWebAclId() : "");

        List<Origin> origins = cfg.getOrigins();
        xml.raw(xmlQuantityItems("Origins", "Origin", origins != null ? origins.size() : 0,
                origins != null ? origins.stream().map(this::xmlOrigin).toList() : List.of()));

        if (cfg.getDefaultCacheBehavior() != null) {
            xml.raw(xmlDefaultCacheBehavior(cfg.getDefaultCacheBehavior()));
        }

        List<CacheBehavior> cacheBehaviors = cfg.getCacheBehaviors();
        int cbCount = cacheBehaviors != null ? cacheBehaviors.size() : 0;
        xml.start("CacheBehaviors").elem("Quantity", cbCount);
        if (cbCount > 0) {
            xml.start("Items");
            for (CacheBehavior cb : cacheBehaviors) {
                xml.raw(xmlCacheBehavior(cb));
            }
            xml.end("Items");
        }
        xml.end("CacheBehaviors");

        List<Map<String, Object>> customErrors = cfg.getCustomErrorResponses();
        int ceCount = customErrors != null ? customErrors.size() : 0;
        xml.start("CustomErrorResponses").elem("Quantity", ceCount);
        if (ceCount > 0) {
            xml.start("Items");
            for (Map<String, Object> ce : customErrors) {
                xml.start("CustomErrorResponse")
                        .elem("ErrorCode", ce.get("ErrorCode") != null
                                ? String.valueOf(ce.get("ErrorCode")) : null)
                        .elem("ResponsePagePath", ce.get("ResponsePagePath") != null
                                ? String.valueOf(ce.get("ResponsePagePath")) : null)
                        .elem("ResponseCode", ce.get("ResponseCode") != null
                                ? String.valueOf(ce.get("ResponseCode")) : null)
                        .elem("ErrorCachingMinTTL", ce.get("ErrorCachingMinTTL") != null
                                ? String.valueOf(ce.get("ErrorCachingMinTTL")) : null)
                        .end("CustomErrorResponse");
            }
            xml.end("Items");
        }
        xml.end("CustomErrorResponses");

        xml.raw(xmlRestrictions(cfg.getGeoRestriction()));

        List<String> aliases = cfg.getAliases();
        int aliasCount = aliases != null ? aliases.size() : 0;
        xml.start("Aliases").elem("Quantity", aliasCount);
        if (aliasCount > 0) {
            xml.start("Items");
            for (String a : aliases) {
                xml.elem("CNAME", a);
            }
            xml.end("Items");
        }
        xml.end("Aliases");

        xml.raw(xmlViewerCertificate(cfg.getViewerCertificate()));

        return xml.build();
    }

    @SuppressWarnings("unchecked")
    private String xmlRestrictions(Map<String, Object> geo) {
        XmlBuilder xml = new XmlBuilder().start("Restrictions").start("GeoRestriction");
        Object restrictionType = geo != null ? geo.get("RestrictionType") : null;
        if (restrictionType != null && !"none".equals(restrictionType)) {
            List<String> locations = geo.get("Items") instanceof List<?> items
                    ? (List<String>) items
                    : List.of();
            xml.elem("RestrictionType", String.valueOf(restrictionType))
                    .elem("Quantity", locations.size());
            if (!locations.isEmpty()) {
                xml.start("Items");
                for (String location : locations) {
                    xml.elem("Location", location);
                }
                xml.end("Items");
            }
        } else {
            xml.elem("RestrictionType", "none").elem("Quantity", 0);
        }
        xml.end("GeoRestriction").end("Restrictions");
        return xml.build();
    }

    private String xmlOrigin(Origin o) {
        XmlBuilder xml = new XmlBuilder()
                .start("Origin")
                .elem("Id", o.getId())
                .elem("DomainName", o.getDomainName())
                .elem("OriginPath", o.getOriginPath() != null ? o.getOriginPath() : "")
                .elem("ConnectionAttempts", o.getConnectionAttempts())
                .elem("ConnectionTimeout", o.getConnectionTimeout());

        if (o.getOriginAccessControlId() != null && !o.getOriginAccessControlId().isEmpty()) {
            xml.elem("OriginAccessControlId", o.getOriginAccessControlId());
        }

        List<Map<String, String>> customHeaders = o.getCustomHeaders();
        int headerCount = customHeaders != null ? customHeaders.size() : 0;
        xml.start("CustomHeaders").elem("Quantity", headerCount);
        if (headerCount > 0) {
            xml.start("Items");
            for (Map<String, String> header : customHeaders) {
                xml.start("OriginCustomHeader")
                        .elem("HeaderName", header.get("HeaderName"))
                        .elem("HeaderValue", header.get("HeaderValue"))
                        .end("OriginCustomHeader");
            }
            xml.end("Items");
        }
        xml.end("CustomHeaders");

        Map<String, String> s3Config = o.getS3OriginConfig();
        if (s3Config != null) {
            xml.start("S3OriginConfig")
                    .elem("OriginAccessIdentity", s3Config.getOrDefault("OriginAccessIdentity", ""))
                    .end("S3OriginConfig");
        } else if (o.getCustomOriginConfig() == null && o.getVpcOriginConfig() == null) {
            xml.start("S3OriginConfig").elem("OriginAccessIdentity", "").end("S3OriginConfig");
        }

        if (o.getCustomOriginConfig() != null) {
            Map<String, Object> coc = o.getCustomOriginConfig();
            xml.start("CustomOriginConfig")
                    .elem("HTTPPort", coc.getOrDefault("HTTPPort", "80").toString())
                    .elem("HTTPSPort", coc.getOrDefault("HTTPSPort", "443").toString())
                    .elem("OriginProtocolPolicy",
                            coc.getOrDefault("OriginProtocolPolicy", "https-only").toString())
                    .end("CustomOriginConfig");
        }

        if (o.getVpcOriginConfig() != null) {
            Map<String, String> voc = o.getVpcOriginConfig();
            xml.start("VpcOriginConfig")
                    .elem("VpcOriginId", voc.getOrDefault("VpcOriginId", ""));
            if (voc.containsKey("OriginReadTimeout")) {
                xml.elem("OriginReadTimeout", voc.get("OriginReadTimeout"));
            }
            if (voc.containsKey("OriginKeepaliveTimeout")) {
                xml.elem("OriginKeepaliveTimeout", voc.get("OriginKeepaliveTimeout"));
            }
            xml.end("VpcOriginConfig");
        }

        xml.end("Origin");
        return xml.build();
    }

    private String xmlDefaultCacheBehavior(DefaultCacheBehavior dcb) {
        XmlBuilder xml = new XmlBuilder()
                .start("DefaultCacheBehavior")
                .elem("TargetOriginId", dcb.getTargetOriginId())
                .elem("ViewerProtocolPolicy",
                        dcb.getViewerProtocolPolicy() != null ? dcb.getViewerProtocolPolicy() : "redirect-to-https")
                .elem("CachePolicyId", dcb.getCachePolicyId())
                .elem("OriginRequestPolicyId", dcb.getOriginRequestPolicyId())
                .elem("ResponseHeadersPolicyId", dcb.getResponseHeadersPolicyId())
                .elem("Compress", dcb.isCompress());

        xml.raw(xmlAllowedMethods(dcb.getAllowedMethods(), dcb.getCachedMethods()));

        if (dcb.getForwardedValues() != null) {
            xml.raw(xmlForwardedValues(dcb.getForwardedValues()));
        }
        if (dcb.getMinTTL() != null) {
            xml.elem("MinTTL", dcb.getMinTTL().longValue());
        }
        if (dcb.getDefaultTTL() != null) {
            xml.elem("DefaultTTL", dcb.getDefaultTTL().longValue());
        }
        if (dcb.getMaxTTL() != null) {
            xml.elem("MaxTTL", dcb.getMaxTTL().longValue());
        }

        xml.raw(xmlFunctionAssociations(dcb.getFunctionAssociations()));
        xml.start("LambdaFunctionAssociations").elem("Quantity", 0).end("LambdaFunctionAssociations");

        xml.end("DefaultCacheBehavior");
        return xml.build();
    }

    private String xmlCacheBehavior(CacheBehavior cb) {
        XmlBuilder xml = new XmlBuilder()
                .start("CacheBehavior")
                .elem("PathPattern", cb.getPathPattern())
                .elem("TargetOriginId", cb.getTargetOriginId())
                .elem("ViewerProtocolPolicy",
                        cb.getViewerProtocolPolicy() != null ? cb.getViewerProtocolPolicy() : "redirect-to-https")
                .elem("CachePolicyId", cb.getCachePolicyId())
                .elem("OriginRequestPolicyId", cb.getOriginRequestPolicyId())
                .elem("ResponseHeadersPolicyId", cb.getResponseHeadersPolicyId())
                .elem("Compress", cb.isCompress());

        xml.raw(xmlAllowedMethods(cb.getAllowedMethods(), cb.getCachedMethods()));

        if (cb.getForwardedValues() != null) {
            xml.raw(xmlForwardedValues(cb.getForwardedValues()));
        }
        if (cb.getMinTTL() != null) {
            xml.elem("MinTTL", cb.getMinTTL().longValue());
        }
        if (cb.getDefaultTTL() != null) {
            xml.elem("DefaultTTL", cb.getDefaultTTL().longValue());
        }
        if (cb.getMaxTTL() != null) {
            xml.elem("MaxTTL", cb.getMaxTTL().longValue());
        }

        xml.raw(xmlFunctionAssociations(cb.getFunctionAssociations()));
        xml.start("LambdaFunctionAssociations").elem("Quantity", 0).end("LambdaFunctionAssociations");

        xml.end("CacheBehavior");
        return xml.build();
    }

    private String xmlAllowedMethods(List<String> allowed, List<String> cached) {
        if (allowed == null || allowed.isEmpty()) {
            allowed = List.of("GET", "HEAD");
        }
        if (cached == null || cached.isEmpty()) {
            cached = List.of("GET", "HEAD");
        }
        XmlBuilder xml = new XmlBuilder()
                .start("AllowedMethods").elem("Quantity", allowed.size()).start("Items");
        for (String method : allowed) {
            xml.elem("Method", method);
        }
        xml.end("Items");
        xml.start("CachedMethods").elem("Quantity", cached.size()).start("Items");
        for (String method : cached) {
            xml.elem("Method", method);
        }
        xml.end("Items").end("CachedMethods");
        xml.end("AllowedMethods");
        return xml.build();
    }

    @SuppressWarnings("unchecked")
    private String xmlForwardedValues(Map<String, Object> fv) {
        XmlBuilder xml = new XmlBuilder()
                .start("ForwardedValues")
                .elem("QueryString", Boolean.TRUE.equals(fv.get("QueryString")));

        Map<String, Object> cookies = fv.get("Cookies") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : null;
        xml.start("Cookies").elem("Forward",
                cookies != null ? String.valueOf(cookies.getOrDefault("Forward", "none")) : "none");
        List<String> whitelisted = cookies != null && cookies.get("WhitelistedNames") instanceof List<?> names
                ? (List<String>) names
                : null;
        if (whitelisted != null && !whitelisted.isEmpty()) {
            xml.start("WhitelistedNames").elem("Quantity", whitelisted.size()).start("Items");
            for (String name : whitelisted) {
                xml.elem("Name", name);
            }
            xml.end("Items").end("WhitelistedNames");
        }
        xml.end("Cookies");

        List<String> headers = fv.get("Headers") instanceof List<?> headerNames
                ? (List<String>) headerNames
                : null;
        xml.start("Headers").elem("Quantity", headers != null ? headers.size() : 0);
        if (headers != null && !headers.isEmpty()) {
            xml.start("Items");
            for (String name : headers) {
                xml.elem("Name", name);
            }
            xml.end("Items");
        }
        xml.end("Headers");

        List<String> queryStringCacheKeys = fv.get("QueryStringCacheKeys") instanceof List<?> keys
                ? (List<String>) keys
                : null;
        xml.start("QueryStringCacheKeys")
                .elem("Quantity", queryStringCacheKeys != null ? queryStringCacheKeys.size() : 0);
        if (queryStringCacheKeys != null && !queryStringCacheKeys.isEmpty()) {
            xml.start("Items");
            for (String key : queryStringCacheKeys) {
                xml.elem("Name", key);
            }
            xml.end("Items");
        }
        xml.end("QueryStringCacheKeys");

        xml.end("ForwardedValues");
        return xml.build();
    }

    private String xmlViewerCertificate(Map<String, String> vc) {
        XmlBuilder xml = new XmlBuilder().start("ViewerCertificate");
        if (vc != null && !vc.isEmpty()) {
            for (Map.Entry<String, String> entry : vc.entrySet()) {
                xml.elem(entry.getKey(), entry.getValue());
            }
        } else {
            xml.elem("CloudFrontDefaultCertificate", "true")
                    .elem("MinimumProtocolVersion", "TLSv1.2_2021");
        }
        xml.end("ViewerCertificate");
        return xml.build();
    }

    private String xmlDistributionSummary(Distribution d) {
        XmlBuilder xml = new XmlBuilder()
                .start("DistributionSummary")
                .elem("Id", d.getId())
                .elem("ARN", d.getArn())
                .elem("Status", d.getStatus())
                .elem("DomainName", d.getDomainName())
                .elem("LastModifiedTime",
                        d.getLastModifiedTime() != null ? d.getLastModifiedTime().toString() : "")
                .elem("Comment", d.getConfig() != null && d.getConfig().getComment() != null
                        ? d.getConfig().getComment() : "")
                .elem("Enabled", d.getConfig() != null && d.getConfig().isEnabled())
                .elem("HttpVersion", d.getConfig() != null && d.getConfig().getHttpVersion() != null
                        ? d.getConfig().getHttpVersion() : "http2")
                .elem("PriceClass", d.getConfig() != null && d.getConfig().getPriceClass() != null
                        ? d.getConfig().getPriceClass() : "PriceClass_All")
                .elem("IsIPV6Enabled", d.getConfig() != null && d.getConfig().isIPV6Enabled())
                .elem("WebAclId", "");

        DistributionConfig cfg = d.getConfig();
        List<Origin> origins = cfg != null ? cfg.getOrigins() : null;
        xml.raw(xmlQuantityItems("Origins", "Origin",
                origins != null ? origins.size() : 0,
                origins != null ? origins.stream().map(o ->
                        "<Origin><Id>" + XmlBuilder.escape(o.getId()) + "</Id><DomainName>"
                                + XmlBuilder.escape(o.getDomainName()) + "</DomainName></Origin>").toList()
                        : List.of()));

        List<String> aliases = cfg != null ? cfg.getAliases() : null;
        int aliasCount = aliases != null ? aliases.size() : 0;
        xml.start("Aliases").elem("Quantity", aliasCount);
        if (aliasCount > 0) {
            xml.start("Items");
            for (String a : aliases) {
                xml.elem("CNAME", a);
            }
            xml.end("Items");
        }
        xml.end("Aliases");

        xml.raw(xmlViewerCertificate(cfg != null ? cfg.getViewerCertificate() : null));

        xml.end("DistributionSummary");
        return xml.build();
    }

    private String xmlInvalidationBody(Invalidation inv) {
        XmlBuilder xml = new XmlBuilder()
                .elem("Id", inv.getId())
                .elem("Status", inv.getStatus())
                .elem("CreateTime", inv.getCreateTime() != null ? inv.getCreateTime().toString() : "")
                .start("InvalidationBatch");
        List<String> paths = inv.getPaths();
        int pathCount = paths != null ? paths.size() : 0;
        xml.start("Paths").elem("Quantity", pathCount);
        if (pathCount > 0) {
            xml.start("Items");
            for (String p : paths) {
                xml.elem("Path", p);
            }
            xml.end("Items");
        }
        xml.end("Paths")
                .elem("CallerReference", inv.getCallerReference() != null ? inv.getCallerReference() : "")
                .end("InvalidationBatch");
        return xml.build();
    }

    private String xmlCachePolicyResponse(CachePolicy policy) {
        XmlBuilder xml = new XmlBuilder()
                .start("CachePolicy")
                .elem("Id", policy.getId())
                .elem("LastModifiedTime",
                        policy.getLastModifiedTime() != null ? policy.getLastModifiedTime().toString() : "");
        if (policy.getConfigXml() != null && !policy.getConfigXml().isBlank()) {
            xml.raw(policy.getConfigXml());
        } else {
            xml.start("CachePolicyConfig")
                    .elem("Name", policy.getName())
                    .elem("Comment", policy.getComment() != null ? policy.getComment() : "")
                    .end("CachePolicyConfig");
        }
        return xml.end("CachePolicy").build();
    }

    private String xmlOriginRequestPolicyResponse(OriginRequestPolicy policy) {
        XmlBuilder xml = new XmlBuilder()
                .start("OriginRequestPolicy")
                .elem("Id", policy.getId())
                .elem("LastModifiedTime",
                        policy.getLastModifiedTime() != null ? policy.getLastModifiedTime().toString() : "");
        if (policy.getConfigXml() != null && !policy.getConfigXml().isBlank()) {
            xml.raw(policy.getConfigXml());
        } else {
            xml.start("OriginRequestPolicyConfig")
                    .elem("Name", policy.getName())
                    .elem("Comment", policy.getComment() != null ? policy.getComment() : "")
                    .end("OriginRequestPolicyConfig");
        }
        return xml.end("OriginRequestPolicy").build();
    }

    private String xmlResponseHeadersPolicyResponse(ResponseHeadersPolicy policy) {
        XmlBuilder xml = new XmlBuilder()
                .start("ResponseHeadersPolicy")
                .elem("Id", policy.getId())
                .elem("LastModifiedTime",
                        policy.getLastModifiedTime() != null ? policy.getLastModifiedTime().toString() : "");
        if (policy.getConfigXml() != null && !policy.getConfigXml().isBlank()) {
            xml.raw(policy.getConfigXml());
        } else {
            xml.start("ResponseHeadersPolicyConfig")
                    .elem("Name", policy.getName())
                    .elem("Comment", policy.getComment() != null ? policy.getComment() : "")
                    .end("ResponseHeadersPolicyConfig");
        }
        return xml.end("ResponseHeadersPolicy").build();
    }

    private String xmlOriginAccessControlResponse(OriginAccessControl oac) {
        return new XmlBuilder()
                .start("OriginAccessControl")
                .elem("Id", oac.getId())
                .start("OriginAccessControlConfig")
                .elem("Name", oac.getName())
                .elem("Description", oac.getDescription() != null ? oac.getDescription() : "")
                .elem("SigningProtocol", oac.getSigningProtocol())
                .elem("SigningBehavior", oac.getSigningBehavior())
                .elem("OriginAccessControlOriginType", oac.getOriginAccessControlOriginType())
                .end("OriginAccessControlConfig")
                .end("OriginAccessControl")
                .build();
    }

    private String xmlOriginAccessControlSummary(OriginAccessControl oac) {
        return new XmlBuilder()
                .start("OriginAccessControlSummary")
                .elem("Id", oac.getId())
                .elem("Name", oac.getName())
                .elem("Description", oac.getDescription() != null ? oac.getDescription() : "")
                .elem("SigningProtocol", oac.getSigningProtocol())
                .elem("SigningBehavior", oac.getSigningBehavior())
                .elem("OriginAccessControlOriginType", oac.getOriginAccessControlOriginType())
                .elem("LastModifiedTime",
                        oac.getLastModifiedTime() != null ? oac.getLastModifiedTime().toString() : "")
                .end("OriginAccessControlSummary")
                .build();
    }

    private String xmlOaiResponse(CloudFrontOriginAccessIdentity oai) {
        return new XmlBuilder()
                .start("CloudFrontOriginAccessIdentity")
                .elem("Id", oai.getId())
                .elem("S3CanonicalUserId", oai.getS3CanonicalUserId())
                .start("CloudFrontOriginAccessIdentityConfig")
                .elem("CallerReference", oai.getCallerReference())
                .elem("Comment", oai.getComment() != null ? oai.getComment() : "")
                .end("CloudFrontOriginAccessIdentityConfig")
                .end("CloudFrontOriginAccessIdentity")
                .build();
    }

    private String xmlFunctionResponse(CloudFrontFunction fn) {
        return new XmlBuilder()
                .start("FunctionSummary")
                .elem("Name", fn.getName())
                .elem("Status", fn.getStatus())
                .start("FunctionConfig")
                .elem("Comment", fn.getComment() != null ? fn.getComment() : "")
                .elem("Runtime", fn.getRuntime() != null ? fn.getRuntime() : "cloudfront-js-2.0")
                .raw(xmlKeyValueStoreAssociations(fn.getKeyValueStoreArns()))
                .end("FunctionConfig")
                .start("FunctionMetadata")
                .elem("FunctionARN",
                        AwsArnUtils.Arn.of("cloudfront", "", service.getAccountId(), "function/" + fn.getName()).toString())
                .elem("Stage", fn.getStage())
                .elem("CreatedTime", fn.getCreatedTime() != null ? fn.getCreatedTime().toString() : "")
                .elem("LastModifiedTime",
                        fn.getLastModifiedTime() != null ? fn.getLastModifiedTime().toString() : "")
                .end("FunctionMetadata")
                .end("FunctionSummary")
                .build();
    }

    private String xmlFunctionAssociations(List<Map<String, String>> associations) {
        List<Map<String, String>> items = associations != null ? associations : List.of();
        XmlBuilder xml = new XmlBuilder()
                .start("FunctionAssociations")
                .elem("Quantity", items.size());
        if (!items.isEmpty()) {
            xml.start("Items");
            for (Map<String, String> association : items) {
                xml.start("FunctionAssociation")
                        .elem("FunctionARN", association.get("FunctionARN"))
                        .elem("EventType", association.get("EventType"))
                        .end("FunctionAssociation");
            }
            xml.end("Items");
        }
        xml.end("FunctionAssociations");
        return xml.build();
    }

    private String xmlKeyValueStoreAssociations(List<String> arns) {
        List<String> items = arns != null ? arns : List.of();
        XmlBuilder xml = new XmlBuilder()
                .start("KeyValueStoreAssociations")
                .elem("Quantity", items.size());
        if (!items.isEmpty()) {
            xml.start("Items");
            for (String arn : items) {
                xml.start("KeyValueStoreAssociation")
                        .elem("KeyValueStoreARN", arn)
                        .end("KeyValueStoreAssociation");
            }
            xml.end("Items");
        }
        xml.end("KeyValueStoreAssociations");
        return xml.build();
    }

    private String xmlVpcOrigin(VpcOrigin origin) {
        List<String> protocols = origin.getOriginSslProtocols() != null
                ? origin.getOriginSslProtocols() : List.of();
        XmlBuilder xml = new XmlBuilder()
                .start("VpcOrigin")
                .elem("Id", origin.getId())
                .elem("Arn", origin.getArn() != null ? origin.getArn() : "")
                .elem("AccountId", origin.getAccountId() != null ? origin.getAccountId() : "")
                .elem("Status", origin.getStatus() != null ? origin.getStatus() : "Deployed")
                .elem("CreatedTime", origin.getCreatedTime() != null ? origin.getCreatedTime().toString() : "")
                .elem("LastModifiedTime",
                        origin.getLastModifiedTime() != null ? origin.getLastModifiedTime().toString() : "")
                .start("VpcOriginEndpointConfig")
                .elem("Name", origin.getName() != null ? origin.getName() : "")
                .elem("Arn", origin.getOriginEndpointArn() != null ? origin.getOriginEndpointArn() : "")
                .elem("HTTPPort", origin.getHttpPort())
                .elem("HTTPSPort", origin.getHttpsPort())
                .elem("OriginProtocolPolicy",
                        origin.getOriginProtocolPolicy() != null ? origin.getOriginProtocolPolicy() : "https-only")
                .start("OriginSslProtocols")
                .elem("Quantity", protocols.size());
        if (!protocols.isEmpty()) {
            xml.start("Items");
            for (String protocol : protocols) {
                xml.elem("SslProtocol", protocol);
            }
            xml.end("Items");
        }
        xml.end("OriginSslProtocols")
                .end("VpcOriginEndpointConfig")
                .end("VpcOrigin");
        return xml.build();
    }

    private String xmlVpcOriginSummary(VpcOrigin origin) {
        return new XmlBuilder()
                .start("VpcOriginSummary")
                .elem("Id", origin.getId())
                .elem("Name", origin.getName() != null ? origin.getName() : "")
                .elem("Status", origin.getStatus() != null ? origin.getStatus() : "Deployed")
                .elem("CreatedTime", origin.getCreatedTime() != null ? origin.getCreatedTime().toString() : "")
                .elem("LastModifiedTime",
                        origin.getLastModifiedTime() != null ? origin.getLastModifiedTime().toString() : "")
                .elem("Arn", origin.getArn() != null ? origin.getArn() : "")
                .elem("AccountId", origin.getAccountId() != null ? origin.getAccountId() : "")
                .elem("OriginEndpointArn",
                        origin.getOriginEndpointArn() != null ? origin.getOriginEndpointArn() : "")
                .end("VpcOriginSummary")
                .build();
    }

    private String xmlKeyValueStore(KeyValueStore store) {
        return new XmlBuilder()
                .start("KeyValueStore")
                .elem("Name", store.getName() != null ? store.getName() : "")
                .elem("Id", store.getId())
                .elem("Comment", store.getComment() != null ? store.getComment() : "")
                .elem("ARN", store.getArn() != null ? store.getArn() : "")
                .elem("Status", store.getStatus() != null ? store.getStatus() : "READY")
                .elem("LastModifiedTime",
                        store.getLastModifiedTime() != null ? store.getLastModifiedTime().toString() : "")
                .end("KeyValueStore")
                .build();
    }

    private String xmlQuantityItems(String wrapper, String itemTag, int count, List<String> items) {
        XmlBuilder xml = new XmlBuilder().start(wrapper).elem("Quantity", count);
        if (count > 0 && items != null && !items.isEmpty()) {
            xml.start("Items");
            for (String item : items) {
                xml.raw(item);
            }
            xml.end("Items");
        }
        xml.end(wrapper);
        return xml.build();
    }

    private Response xmlErrorResponse(AwsException e) {
        String xml = new XmlBuilder()
                .start("ErrorResponse", NS)
                .start("Error")
                .elem("Type", "Client")
                .elem("Code", e.getErrorCode())
                .elem("Message", e.getMessage())
                .end("Error")
                .elem("RequestId", "00000000-0000-0000-0000-000000000000")
                .end("ErrorResponse")
                .build();
        return Response.status(e.getHttpStatus()).type(XML).entity(xml).build();
    }

    // ── Request parsers ───────────────────────────────────────────────────────

    private DistributionConfig parseDistributionConfig(String body) {
        DistributionConfig cfg = new DistributionConfig();
        cfg.setCallerReference(XmlParser.extractFirst(body, "CallerReference", null));
        cfg.setEnabled("true".equalsIgnoreCase(XmlParser.extractFirst(body, "Enabled", "true")));
        cfg.setComment(XmlParser.extractFirst(body, "Comment", ""));
        cfg.setDefaultRootObject(XmlParser.extractFirst(body, "DefaultRootObject", ""));
        cfg.setHttpVersion(XmlParser.extractFirst(body, "HttpVersion", "http2"));
        cfg.setPriceClass(XmlParser.extractFirst(body, "PriceClass", "PriceClass_All"));
        cfg.setIPV6Enabled("true".equalsIgnoreCase(XmlParser.extractFirst(body, "IsIPV6Enabled", "true")));
        cfg.setWebAclId(XmlParser.extractFirst(body, "WebAclId", null));
        cfg.setContinuousDeploymentPolicyId(XmlParser.extractFirst(body, "ContinuousDeploymentPolicyId", null));
        cfg.setStaging("true".equalsIgnoreCase(XmlParser.extractFirst(body, "Staging", "false")));

        cfg.setOrigins(parseOrigins(body));
        cfg.setDefaultCacheBehavior(parseDefaultCacheBehavior(body));
        cfg.setCacheBehaviors(parseCacheBehaviors(body));
        cfg.setAliases(parseAliases(body));
        cfg.setViewerCertificate(parseViewerCertificate(body));
        cfg.setGeoRestriction(parseGeoRestriction(body));
        cfg.setCustomErrorResponses(parseCustomErrorResponses(body));

        return cfg;
    }

    private Map<String, Object> parseGeoRestriction(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inGeoRestriction = false;
            String restrictionType = null;
            List<String> locations = new ArrayList<>();

            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "GeoRestriction" -> inGeoRestriction = true;
                        case "RestrictionType" -> {
                            if (inGeoRestriction) restrictionType = r.getElementText();
                        }
                        case "Location" -> {
                            if (inGeoRestriction) locations.add(r.getElementText());
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "GeoRestriction".equals(r.getLocalName())) {
                    inGeoRestriction = false;
                }
            }
            r.close();
            if (restrictionType != null) {
                Map<String, Object> geo = new LinkedHashMap<>();
                geo.put("RestrictionType", restrictionType);
                geo.put("Items", locations);
                return geo;
            }
        } catch (Exception e) {
            LOG.debugv("Failed to parse GeoRestriction: {0}", e.getMessage());
        }
        return null;
    }

    private List<Map<String, Object>> parseCustomErrorResponses(String body) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inList = false;
            Map<String, Object> current = null;

            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "CustomErrorResponses" -> inList = true;
                        case "CustomErrorResponse" -> {
                            if (inList) current = new LinkedHashMap<>();
                        }
                        case "ErrorCode" -> {
                            if (current != null) {
                                try {
                                    current.put("ErrorCode", Integer.parseInt(r.getElementText()));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        case "ResponsePagePath" -> {
                            if (current != null) current.put("ResponsePagePath", r.getElementText());
                        }
                        case "ResponseCode" -> {
                            if (current != null) current.put("ResponseCode", r.getElementText());
                        }
                        case "ErrorCachingMinTTL" -> {
                            if (current != null) {
                                try {
                                    current.put("ErrorCachingMinTTL", Long.parseLong(r.getElementText()));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "CustomErrorResponse" -> {
                            if (current != null) {
                                result.add(current);
                                current = null;
                            }
                        }
                        case "CustomErrorResponses" -> inList = false;
                        default -> {
                        }
                    }
                }
            }
            r.close();
        } catch (Exception e) {
            LOG.debugv("Failed to parse CustomErrorResponses: {0}", e.getMessage());
        }
        return result;
    }

    private List<Origin> parseOrigins(String body) {
        List<Origin> result = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inOrigins = false;
            boolean inOrigin = false;
            boolean inS3OriginConfig = false;
            boolean inCustomOriginConfig = false;
            boolean inVpcOriginConfig = false;
            boolean inCustomHeaders = false;
            Origin current = null;
            Map<String, String> s3Config = null;
            Map<String, Object> customConfig = null;
            Map<String, String> vpcConfig = null;
            List<Map<String, String>> customHeaders = new ArrayList<>();
            Map<String, String> currentHeader = null;

            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    switch (local) {
                        case "Origins" -> inOrigins = true;
                        case "Origin" -> {
                            if (inOrigins) {
                                inOrigin = true;
                                current = new Origin();
                                customHeaders = new ArrayList<>();
                            }
                        }
                        case "CustomHeaders" -> {
                            if (inOrigin) inCustomHeaders = true;
                        }
                        case "OriginCustomHeader" -> {
                            if (inCustomHeaders) currentHeader = new LinkedHashMap<>();
                        }
                        case "HeaderName" -> {
                            if (currentHeader != null) {
                                currentHeader.put("HeaderName", r.getElementText());
                            }
                        }
                        case "HeaderValue" -> {
                            if (currentHeader != null) {
                                currentHeader.put("HeaderValue", r.getElementText());
                            }
                        }
                        case "S3OriginConfig" -> {
                            if (inOrigin) {
                                inS3OriginConfig = true;
                                s3Config = new LinkedHashMap<>();
                            }
                        }
                        case "CustomOriginConfig" -> {
                            if (inOrigin) {
                                inCustomOriginConfig = true;
                                customConfig = new LinkedHashMap<>();
                            }
                        }
                        case "VpcOriginConfig" -> {
                            if (inOrigin) {
                                inVpcOriginConfig = true;
                                vpcConfig = new LinkedHashMap<>();
                            }
                        }
                        case "Id" -> {
                            if (inOrigin && !inS3OriginConfig && !inCustomOriginConfig
                                    && !inVpcOriginConfig && current != null) {
                                current.setId(r.getElementText());
                            }
                        }
                        case "DomainName" -> {
                            if (inOrigin && !inS3OriginConfig && !inCustomOriginConfig
                                    && !inVpcOriginConfig && current != null) {
                                current.setDomainName(r.getElementText());
                            }
                        }
                        case "OriginPath" -> {
                            if (inOrigin && !inS3OriginConfig && !inCustomOriginConfig
                                    && !inVpcOriginConfig && current != null) {
                                current.setOriginPath(r.getElementText());
                            }
                        }
                        case "OriginAccessControlId" -> {
                            if (inOrigin && !inS3OriginConfig && !inCustomOriginConfig
                                    && !inVpcOriginConfig && current != null) {
                                current.setOriginAccessControlId(r.getElementText());
                            }
                        }
                        case "ConnectionAttempts" -> {
                            if (inOrigin && !inS3OriginConfig && !inCustomOriginConfig
                                    && !inVpcOriginConfig && current != null) {
                                try {
                                    current.setConnectionAttempts(Integer.parseInt(r.getElementText()));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        case "ConnectionTimeout" -> {
                            if (inOrigin && !inS3OriginConfig && !inCustomOriginConfig
                                    && !inVpcOriginConfig && current != null) {
                                try {
                                    current.setConnectionTimeout(Integer.parseInt(r.getElementText()));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        case "VpcOriginId", "OriginReadTimeout", "OriginKeepaliveTimeout" -> {
                            if (inVpcOriginConfig && vpcConfig != null) {
                                vpcConfig.put(local, r.getElementText());
                            }
                        }
                        case "OriginAccessIdentity" -> {
                            if (inS3OriginConfig && s3Config != null) {
                                s3Config.put("OriginAccessIdentity", r.getElementText());
                            }
                        }
                        case "HTTPPort" -> {
                            if (inCustomOriginConfig && customConfig != null) {
                                customConfig.put("HTTPPort", r.getElementText());
                            }
                        }
                        case "HTTPSPort" -> {
                            if (inCustomOriginConfig && customConfig != null) {
                                customConfig.put("HTTPSPort", r.getElementText());
                            }
                        }
                        case "OriginProtocolPolicy" -> {
                            if (inCustomOriginConfig && customConfig != null) {
                                customConfig.put("OriginProtocolPolicy", r.getElementText());
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "OriginCustomHeader" -> {
                            if (currentHeader != null) {
                                customHeaders.add(currentHeader);
                                currentHeader = null;
                            }
                        }
                        case "CustomHeaders" -> inCustomHeaders = false;
                        case "S3OriginConfig" -> {
                            if (inS3OriginConfig && current != null) {
                                current.setS3OriginConfig(s3Config);
                            }
                            inS3OriginConfig = false;
                            s3Config = null;
                        }
                        case "CustomOriginConfig" -> {
                            if (inCustomOriginConfig && current != null) {
                                current.setCustomOriginConfig(customConfig);
                            }
                            inCustomOriginConfig = false;
                            customConfig = null;
                        }
                        case "VpcOriginConfig" -> {
                            if (inVpcOriginConfig && current != null) {
                                current.setVpcOriginConfig(vpcConfig);
                            }
                            inVpcOriginConfig = false;
                            vpcConfig = null;
                        }
                        case "Origin" -> {
                            if (inOrigin && current != null) {
                                if (!customHeaders.isEmpty()) {
                                    current.setCustomHeaders(customHeaders);
                                }
                                result.add(current);
                            }
                            inOrigin = false;
                            current = null;
                        }
                        case "Origins" -> inOrigins = false;
                        default -> {
                        }
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return result;
    }

    private DefaultCacheBehavior parseDefaultCacheBehavior(String body) {
        DefaultCacheBehavior dcb = new DefaultCacheBehavior();
        if (body == null || body.isEmpty()) {
            return dcb;
        }
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inDcb = false;
            boolean inAllowedMethods = false;
            boolean inCachedMethods = false;
            boolean inFunctionAssociations = false;
            boolean inForwardedValues = false;
            boolean inCookies = false;
            boolean inWhitelistedNames = false;
            boolean inFvHeaders = false;
            boolean inQueryStringCacheKeys = false;
            List<String> allowedMethods = new ArrayList<>();
            List<String> cachedMethods = new ArrayList<>();
            List<Map<String, String>> functionAssociations = new ArrayList<>();
            Map<String, String> currentAssociation = null;
            Map<String, Object> forwardedValues = null;
            Map<String, Object> cookies = null;
            List<String> whitelistedNames = new ArrayList<>();
            List<String> fvHeaders = new ArrayList<>();
            List<String> queryStringCacheKeys = new ArrayList<>();

            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    switch (local) {
                        case "DefaultCacheBehavior" -> inDcb = true;
                        case "AllowedMethods" -> {
                            if (inDcb) inAllowedMethods = true;
                        }
                        case "CachedMethods" -> {
                            if (inAllowedMethods) inCachedMethods = true;
                        }
                        case "FunctionAssociations" -> {
                            if (inDcb) inFunctionAssociations = true;
                        }
                        case "FunctionAssociation" -> {
                            if (inFunctionAssociations) currentAssociation = new LinkedHashMap<>();
                        }
                        case "FunctionARN" -> {
                            if (currentAssociation != null) {
                                currentAssociation.put("FunctionARN", r.getElementText());
                            }
                        }
                        case "EventType" -> {
                            if (currentAssociation != null) {
                                currentAssociation.put("EventType", r.getElementText());
                            }
                        }
                        case "ForwardedValues" -> {
                            if (inDcb) {
                                inForwardedValues = true;
                                forwardedValues = new LinkedHashMap<>();
                            }
                        }
                        case "QueryString" -> {
                            if (inForwardedValues && forwardedValues != null) {
                                forwardedValues.put("QueryString",
                                        "true".equalsIgnoreCase(r.getElementText()));
                            }
                        }
                        case "Cookies" -> {
                            if (inForwardedValues) {
                                inCookies = true;
                                cookies = new LinkedHashMap<>();
                            }
                        }
                        case "Forward" -> {
                            if (inCookies && cookies != null) {
                                cookies.put("Forward", r.getElementText());
                            }
                        }
                        case "WhitelistedNames" -> {
                            if (inCookies) inWhitelistedNames = true;
                        }
                        case "Headers" -> {
                            if (inForwardedValues && !inCookies) inFvHeaders = true;
                        }
                        case "QueryStringCacheKeys" -> {
                            if (inForwardedValues) inQueryStringCacheKeys = true;
                        }
                        case "Name" -> {
                            if (inWhitelistedNames) {
                                whitelistedNames.add(r.getElementText());
                            } else if (inFvHeaders) {
                                fvHeaders.add(r.getElementText());
                            } else if (inQueryStringCacheKeys) {
                                queryStringCacheKeys.add(r.getElementText());
                            }
                        }
                        case "MinTTL" -> {
                            if (inDcb) {
                                try {
                                    dcb.setMinTTL(Long.parseLong(r.getElementText()));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        case "DefaultTTL" -> {
                            if (inDcb) {
                                try {
                                    dcb.setDefaultTTL(Long.parseLong(r.getElementText()));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        case "MaxTTL" -> {
                            if (inDcb) {
                                try {
                                    dcb.setMaxTTL(Long.parseLong(r.getElementText()));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        case "TargetOriginId" -> {
                            if (inDcb) dcb.setTargetOriginId(r.getElementText());
                        }
                        case "ViewerProtocolPolicy" -> {
                            if (inDcb) dcb.setViewerProtocolPolicy(r.getElementText());
                        }
                        case "CachePolicyId" -> {
                            if (inDcb) dcb.setCachePolicyId(r.getElementText());
                        }
                        case "OriginRequestPolicyId" -> {
                            if (inDcb) dcb.setOriginRequestPolicyId(r.getElementText());
                        }
                        case "ResponseHeadersPolicyId" -> {
                            if (inDcb) dcb.setResponseHeadersPolicyId(r.getElementText());
                        }
                        case "FieldLevelEncryptionId" -> {
                            if (inDcb) dcb.setFieldLevelEncryptionId(r.getElementText());
                        }
                        case "RealtimeLogConfigArn" -> {
                            if (inDcb) dcb.setRealtimeLogConfigArn(r.getElementText());
                        }
                        case "Compress" -> {
                            if (inDcb) dcb.setCompress("true".equalsIgnoreCase(r.getElementText()));
                        }
                        case "Method" -> {
                            if (inCachedMethods) {
                                cachedMethods.add(r.getElementText());
                            } else if (inAllowedMethods) {
                                allowedMethods.add(r.getElementText());
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "CachedMethods" -> inCachedMethods = false;
                        case "AllowedMethods" -> inAllowedMethods = false;
                        case "FunctionAssociation" -> {
                            if (currentAssociation != null) {
                                functionAssociations.add(currentAssociation);
                                currentAssociation = null;
                            }
                        }
                        case "FunctionAssociations" -> inFunctionAssociations = false;
                        case "WhitelistedNames" -> {
                            if (inWhitelistedNames && cookies != null && !whitelistedNames.isEmpty()) {
                                cookies.put("WhitelistedNames", new ArrayList<>(whitelistedNames));
                            }
                            whitelistedNames.clear();
                            inWhitelistedNames = false;
                        }
                        case "Headers" -> {
                            if (inFvHeaders) {
                                if (forwardedValues != null && !fvHeaders.isEmpty()) {
                                    forwardedValues.put("Headers", new ArrayList<>(fvHeaders));
                                }
                                fvHeaders.clear();
                                inFvHeaders = false;
                            }
                        }
                        case "QueryStringCacheKeys" -> {
                            if (inQueryStringCacheKeys) {
                                if (forwardedValues != null && !queryStringCacheKeys.isEmpty()) {
                                    forwardedValues.put("QueryStringCacheKeys",
                                            new ArrayList<>(queryStringCacheKeys));
                                }
                                queryStringCacheKeys.clear();
                                inQueryStringCacheKeys = false;
                            }
                        }
                        case "Cookies" -> {
                            if (inCookies && forwardedValues != null && cookies != null) {
                                forwardedValues.put("Cookies", cookies);
                            }
                            cookies = null;
                            inCookies = false;
                        }
                        case "ForwardedValues" -> {
                            if (inForwardedValues && forwardedValues != null) {
                                dcb.setForwardedValues(forwardedValues);
                            }
                            forwardedValues = null;
                            inForwardedValues = false;
                        }
                        case "DefaultCacheBehavior" -> inDcb = false;
                        default -> {
                        }
                    }
                }
            }
            r.close();
            if (!allowedMethods.isEmpty()) {
                dcb.setAllowedMethods(allowedMethods);
            }
            if (!cachedMethods.isEmpty()) {
                dcb.setCachedMethods(cachedMethods);
            }
            if (!functionAssociations.isEmpty()) {
                dcb.setFunctionAssociations(functionAssociations);
            }
        } catch (Exception ignored) {
        }
        return dcb;
    }

    private List<CacheBehavior> parseCacheBehaviors(String body) {
        List<CacheBehavior> result = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inCacheBehaviors = false;
            boolean inCacheBehavior = false;
            boolean inAllowedMethods = false;
            boolean inCachedMethods = false;
            boolean inFunctionAssociations = false;
            boolean inForwardedValues = false;
            boolean inCookies = false;
            boolean inWhitelistedNames = false;
            boolean inFvHeaders = false;
            boolean inQueryStringCacheKeys = false;
            CacheBehavior current = null;
            List<String> allowedMethods = new ArrayList<>();
            List<String> cachedMethods = new ArrayList<>();
            List<Map<String, String>> functionAssociations = new ArrayList<>();
            Map<String, String> currentAssociation = null;
            Map<String, Object> forwardedValues = null;
            Map<String, Object> cookies = null;
            List<String> whitelistedNames = new ArrayList<>();
            List<String> fvHeaders = new ArrayList<>();
            List<String> queryStringCacheKeys = new ArrayList<>();

            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    switch (local) {
                        case "CacheBehaviors" -> inCacheBehaviors = true;
                        case "CacheBehavior" -> {
                            if (inCacheBehaviors) {
                                inCacheBehavior = true;
                                current = new CacheBehavior();
                                allowedMethods = new ArrayList<>();
                                cachedMethods = new ArrayList<>();
                                functionAssociations = new ArrayList<>();
                            }
                        }
                        case "AllowedMethods" -> {
                            if (inCacheBehavior) inAllowedMethods = true;
                        }
                        case "CachedMethods" -> {
                            if (inAllowedMethods) inCachedMethods = true;
                        }
                        case "FunctionAssociations" -> {
                            if (inCacheBehavior) inFunctionAssociations = true;
                        }
                        case "FunctionAssociation" -> {
                            if (inFunctionAssociations) currentAssociation = new LinkedHashMap<>();
                        }
                        case "FunctionARN" -> {
                            if (currentAssociation != null) {
                                currentAssociation.put("FunctionARN", r.getElementText());
                            }
                        }
                        case "EventType" -> {
                            if (currentAssociation != null) {
                                currentAssociation.put("EventType", r.getElementText());
                            }
                        }
                        case "ForwardedValues" -> {
                            if (inCacheBehavior) {
                                inForwardedValues = true;
                                forwardedValues = new LinkedHashMap<>();
                            }
                        }
                        case "QueryString" -> {
                            if (inForwardedValues && forwardedValues != null) {
                                forwardedValues.put("QueryString",
                                        "true".equalsIgnoreCase(r.getElementText()));
                            }
                        }
                        case "Cookies" -> {
                            if (inForwardedValues) {
                                inCookies = true;
                                cookies = new LinkedHashMap<>();
                            }
                        }
                        case "Forward" -> {
                            if (inCookies && cookies != null) {
                                cookies.put("Forward", r.getElementText());
                            }
                        }
                        case "WhitelistedNames" -> {
                            if (inCookies) inWhitelistedNames = true;
                        }
                        case "Headers" -> {
                            if (inForwardedValues && !inCookies) inFvHeaders = true;
                        }
                        case "QueryStringCacheKeys" -> {
                            if (inForwardedValues) inQueryStringCacheKeys = true;
                        }
                        case "Name" -> {
                            if (inWhitelistedNames) {
                                whitelistedNames.add(r.getElementText());
                            } else if (inFvHeaders) {
                                fvHeaders.add(r.getElementText());
                            } else if (inQueryStringCacheKeys) {
                                queryStringCacheKeys.add(r.getElementText());
                            }
                        }
                        case "MinTTL" -> {
                            if (inCacheBehavior && current != null) {
                                try {
                                    current.setMinTTL(Long.parseLong(r.getElementText()));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        case "DefaultTTL" -> {
                            if (inCacheBehavior && current != null) {
                                try {
                                    current.setDefaultTTL(Long.parseLong(r.getElementText()));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        case "MaxTTL" -> {
                            if (inCacheBehavior && current != null) {
                                try {
                                    current.setMaxTTL(Long.parseLong(r.getElementText()));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        case "PathPattern" -> {
                            if (inCacheBehavior && current != null) current.setPathPattern(r.getElementText());
                        }
                        case "TargetOriginId" -> {
                            if (inCacheBehavior && current != null) current.setTargetOriginId(r.getElementText());
                        }
                        case "ViewerProtocolPolicy" -> {
                            if (inCacheBehavior && current != null) current.setViewerProtocolPolicy(r.getElementText());
                        }
                        case "CachePolicyId" -> {
                            if (inCacheBehavior && current != null) current.setCachePolicyId(r.getElementText());
                        }
                        case "OriginRequestPolicyId" -> {
                            if (inCacheBehavior && current != null)
                                current.setOriginRequestPolicyId(r.getElementText());
                        }
                        case "ResponseHeadersPolicyId" -> {
                            if (inCacheBehavior && current != null)
                                current.setResponseHeadersPolicyId(r.getElementText());
                        }
                        case "Compress" -> {
                            if (inCacheBehavior && current != null) {
                                current.setCompress("true".equalsIgnoreCase(r.getElementText()));
                            }
                        }
                        case "Method" -> {
                            if (inCachedMethods) {
                                cachedMethods.add(r.getElementText());
                            } else if (inAllowedMethods) {
                                allowedMethods.add(r.getElementText());
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "CachedMethods" -> inCachedMethods = false;
                        case "AllowedMethods" -> inAllowedMethods = false;
                        case "FunctionAssociation" -> {
                            if (currentAssociation != null) {
                                functionAssociations.add(currentAssociation);
                                currentAssociation = null;
                            }
                        }
                        case "FunctionAssociations" -> inFunctionAssociations = false;
                        case "WhitelistedNames" -> {
                            if (inWhitelistedNames && cookies != null && !whitelistedNames.isEmpty()) {
                                cookies.put("WhitelistedNames", new ArrayList<>(whitelistedNames));
                            }
                            whitelistedNames.clear();
                            inWhitelistedNames = false;
                        }
                        case "Headers" -> {
                            if (inFvHeaders) {
                                if (forwardedValues != null && !fvHeaders.isEmpty()) {
                                    forwardedValues.put("Headers", new ArrayList<>(fvHeaders));
                                }
                                fvHeaders.clear();
                                inFvHeaders = false;
                            }
                        }
                        case "QueryStringCacheKeys" -> {
                            if (inQueryStringCacheKeys) {
                                if (forwardedValues != null && !queryStringCacheKeys.isEmpty()) {
                                    forwardedValues.put("QueryStringCacheKeys",
                                            new ArrayList<>(queryStringCacheKeys));
                                }
                                queryStringCacheKeys.clear();
                                inQueryStringCacheKeys = false;
                            }
                        }
                        case "Cookies" -> {
                            if (inCookies && forwardedValues != null && cookies != null) {
                                forwardedValues.put("Cookies", cookies);
                            }
                            cookies = null;
                            inCookies = false;
                        }
                        case "ForwardedValues" -> {
                            if (inForwardedValues && current != null && forwardedValues != null) {
                                current.setForwardedValues(forwardedValues);
                            }
                            forwardedValues = null;
                            inForwardedValues = false;
                        }
                        case "CacheBehavior" -> {
                            if (inCacheBehavior && current != null) {
                                if (!allowedMethods.isEmpty()) {
                                    current.setAllowedMethods(allowedMethods);
                                }
                                if (!cachedMethods.isEmpty()) {
                                    current.setCachedMethods(cachedMethods);
                                }
                                if (!functionAssociations.isEmpty()) {
                                    current.setFunctionAssociations(functionAssociations);
                                }
                                result.add(current);
                            }
                            inCacheBehavior = false;
                            current = null;
                        }
                        case "CacheBehaviors" -> inCacheBehaviors = false;
                        default -> {
                        }
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return result;
    }

    private List<String> parseAliases(String body) {
        List<String> result = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inAliases = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if ("Aliases".equals(local)) {
                        inAliases = true;
                    } else if (inAliases && "CNAME".equals(local)) {
                        result.add(r.getElementText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("Aliases".equals(r.getLocalName())) {
                        inAliases = false;
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return result;
    }

    private Map<String, String> parseViewerCertificate(String body) {
        Map<String, String> result = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inVc = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if ("ViewerCertificate".equals(local)) {
                        inVc = true;
                    } else if (inVc) {
                        String text = r.getElementText();
                        result.put(local, text);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("ViewerCertificate".equals(r.getLocalName())) {
                        inVc = false;
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return result;
    }

    private Invalidation parseInvalidation(String body) {
        Invalidation inv = new Invalidation();
        inv.setCallerReference(XmlParser.extractFirst(body, "CallerReference", null));
        inv.setPaths(XmlParser.extractAll(body, "Path"));
        return inv;
    }

    private CachePolicy parseCachePolicy(String body) {
        CachePolicy policy = new CachePolicy();
        policy.setName(XmlParser.extractFirst(body, "Name", null));
        policy.setComment(XmlParser.extractFirst(body, "Comment", null));
        policy.setConfigXml(extractXmlElement(body, "CachePolicyConfig"));
        return policy;
    }

    private OriginRequestPolicy parseOriginRequestPolicy(String body) {
        OriginRequestPolicy policy = new OriginRequestPolicy();
        policy.setName(XmlParser.extractFirst(body, "Name", null));
        policy.setComment(XmlParser.extractFirst(body, "Comment", null));
        policy.setConfigXml(extractXmlElement(body, "OriginRequestPolicyConfig"));
        return policy;
    }

    private ResponseHeadersPolicy parseResponseHeadersPolicy(String body) {
        ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
        policy.setName(XmlParser.extractFirst(body, "Name", null));
        policy.setComment(XmlParser.extractFirst(body, "Comment", null));
        policy.setConfigXml(extractXmlElement(body, "ResponseHeadersPolicyConfig"));
        return policy;
    }

    private static String extractXmlElement(String body, String localName) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        String pattern = "(?is)<(?:[\\w.-]+:)?" + localName + "\\b[^>]*>.*?</(?:[\\w.-]+:)?" + localName + ">";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(body);
        return matcher.find() ? matcher.group() : null;
    }

    private OriginAccessControl parseOriginAccessControl(String body) {
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName(XmlParser.extractFirst(body, "Name", null));
        oac.setDescription(XmlParser.extractFirst(body, "Description", null));
        oac.setSigningProtocol(XmlParser.extractFirst(body, "SigningProtocol", "sigv4"));
        oac.setSigningBehavior(XmlParser.extractFirst(body, "SigningBehavior", "always"));
        oac.setOriginAccessControlOriginType(XmlParser.extractFirst(body, "OriginAccessControlOriginType", "s3"));
        return oac;
    }

    private CloudFrontOriginAccessIdentity parseOai(String body) {
        CloudFrontOriginAccessIdentity oai = new CloudFrontOriginAccessIdentity();
        oai.setCallerReference(XmlParser.extractFirst(body, "CallerReference", null));
        oai.setComment(XmlParser.extractFirst(body, "Comment", null));
        return oai;
    }

    private CloudFrontFunction parseFunction(String body) {
        CloudFrontFunction fn = new CloudFrontFunction();
        fn.setName(XmlParser.extractFirst(body, "Name", null));
        fn.setComment(XmlParser.extractFirst(body, "Comment", null));
        fn.setRuntime(XmlParser.extractFirst(body, "Runtime", "cloudfront-js-2.0"));
        fn.setFunctionCode(decodeFunctionCode(XmlParser.extractFirst(body, "FunctionCode", null)));
        fn.setKeyValueStoreArns(XmlParser.extractAll(body, "KeyValueStoreARN"));
        return fn;
    }

    /**
     * {@code FunctionCode} is a blob, so the REST-XML wire format carries it
     * base64-encoded. Store the source itself — that is what the runtime runs
     * and what the size limit applies to. Hand-written XML that carries plain
     * source is accepted as-is.
     */
    private String decodeFunctionCode(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value.replaceAll("\\s", ""));
            return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return value;
        }
    }

    private VpcOrigin parseVpcOrigin(String body) {
        VpcOrigin origin = new VpcOrigin();
        origin.setName(XmlParser.extractFirst(body, "Name", null));
        origin.setOriginEndpointArn(XmlParser.extractFirst(body, "Arn", null));
        origin.setOriginProtocolPolicy(XmlParser.extractFirst(body, "OriginProtocolPolicy", "https-only"));
        try {
            origin.setHttpPort(Integer.parseInt(XmlParser.extractFirst(body, "HTTPPort", "80")));
        } catch (NumberFormatException ignored) {
            origin.setHttpPort(80);
        }
        try {
            origin.setHttpsPort(Integer.parseInt(XmlParser.extractFirst(body, "HTTPSPort", "443")));
        } catch (NumberFormatException ignored) {
            origin.setHttpsPort(443);
        }
        List<String> protocols = XmlParser.extractAll(body, "SslProtocol");
        if (!protocols.isEmpty()) {
            origin.setOriginSslProtocols(protocols);
        }
        return origin;
    }

    private KeyValueStore parseKeyValueStore(String body) {
        KeyValueStore store = new KeyValueStore();
        store.setName(XmlParser.extractFirst(body, "Name", null));
        store.setComment(XmlParser.extractFirst(body, "Comment", ""));
        return store;
    }

    // ── Phase 2 XML builders ──────────────────────────────────────────────────

    private String xmlContinuousDeploymentPolicyResponse(ContinuousDeploymentPolicy policy) {
        XmlBuilder xml = new XmlBuilder()
                .start("ContinuousDeploymentPolicy")
                .elem("Id", policy.getId())
                .elem("LastModifiedTime",
                        policy.getLastModifiedTime() != null ? policy.getLastModifiedTime().toString() : "")
                .start("ContinuousDeploymentPolicyConfig")
                .elem("Enabled", policy.isEnabled());
        List<String> dns = policy.getStagingDistributionDnsNames();
        int dnsCount = dns != null ? dns.size() : 0;
        xml.start("StagingDistributionDnsNames").elem("Quantity", dnsCount);
        if (dnsCount > 0) {
            xml.start("Items");
            for (String d : dns) {
                xml.elem("DnsName", d);
            }
            xml.end("Items");
        }
        xml.end("StagingDistributionDnsNames")
                .end("ContinuousDeploymentPolicyConfig")
                .end("ContinuousDeploymentPolicy");
        return xml.build();
    }

    private String xmlPublicKeyResponse(PublicKey key) {
        return new XmlBuilder()
                .start("PublicKey")
                .elem("Id", key.getId())
                .elem("CreatedTime", key.getCreatedTime() != null ? key.getCreatedTime().toString() : "")
                .start("PublicKeyConfig")
                .elem("CallerReference", key.getCallerReference() != null ? key.getCallerReference() : "")
                .elem("Name", key.getName() != null ? key.getName() : "")
                .elem("EncodedKey", key.getEncodedKey() != null ? key.getEncodedKey() : "")
                .elem("Comment", key.getComment() != null ? key.getComment() : "")
                .end("PublicKeyConfig")
                .end("PublicKey")
                .build();
    }

    private String xmlPublicKeySummary(PublicKey key) {
        return new XmlBuilder()
                .start("PublicKeySummary")
                .elem("Id", key.getId())
                .elem("Name", key.getName() != null ? key.getName() : "")
                .elem("CreatedTime", key.getCreatedTime() != null ? key.getCreatedTime().toString() : "")
                .elem("EncodedKey", key.getEncodedKey() != null ? key.getEncodedKey() : "")
                .elem("Comment", key.getComment() != null ? key.getComment() : "")
                .end("PublicKeySummary")
                .build();
    }

    private String xmlKeyGroupResponse(KeyGroup group) {
        List<String> items = group.getItems() != null ? group.getItems() : List.of();
        XmlBuilder xml = new XmlBuilder()
                .start("KeyGroup")
                .elem("Id", group.getId())
                .elem("LastModifiedTime",
                        group.getLastModifiedTime() != null ? group.getLastModifiedTime().toString() : "")
                .start("KeyGroupConfig")
                .elem("Name", group.getName() != null ? group.getName() : "")
                .elem("Comment", group.getComment() != null ? group.getComment() : "")
                .raw(xmlPublicKeyItems(items))
                .end("KeyGroupConfig")
                .end("KeyGroup");
        return xml.build();
    }

    private String xmlRealtimeLogConfigBody(RealtimeLogConfig cfg) {
        List<String> fields = cfg.getFields() != null ? cfg.getFields() : List.of();
        List<Map<String, Object>> endpoints = cfg.getEndPoints() != null ? cfg.getEndPoints() : List.of();
        XmlBuilder xml = new XmlBuilder()
                .start("RealtimeLogConfig")
                .elem("ARN", cfg.getArn() != null ? cfg.getArn() : "")
                .elem("Name", cfg.getName() != null ? cfg.getName() : "")
                .elem("SamplingRate", cfg.getSamplingRate())
                .start("Fields");
        for (String f : fields) {
            xml.elem("Field", f);
        }
        xml.end("Fields");
        xml.start("EndPoints");
        for (Map<String, Object> ep : endpoints) {
            xml.start("EndPoint")
                    .elem("StreamType", String.valueOf(ep.getOrDefault("StreamType", "Kinesis")))
                    .start("KinesisStreamConfig")
                    .elem("RoleARN", ep.get("RoleARN") != null ? String.valueOf(ep.get("RoleARN")) : "")
                    .elem("StreamARN", ep.get("StreamARN") != null ? String.valueOf(ep.get("StreamARN")) : "")
                    .end("KinesisStreamConfig")
                    .end("EndPoint");
        }
        xml.end("EndPoints")
                .end("RealtimeLogConfig");
        return xml.build();
    }

    private String xmlPublicKeyItems(List<String> items) {
        XmlBuilder xml = new XmlBuilder().start("Items");
        if (items != null) {
            for (String item : items) {
                xml.elem("PublicKey", item);
            }
        }
        return xml.end("Items").build();
    }

    private String xmlStreamingDistributionResponse(StreamingDistribution sd) {
        return new XmlBuilder()
                .start("StreamingDistribution", NS)
                .elem("Id", sd.getId())
                .elem("ARN", sd.getArn() != null ? sd.getArn() : "")
                .elem("Status", sd.getStatus())
                .elem("DomainName", sd.getDomainName() != null ? sd.getDomainName() : "")
                .elem("LastModifiedTime",
                        sd.getLastModifiedTime() != null ? sd.getLastModifiedTime().toString() : "")
                .start("ActiveTrustedSigners")
                .elem("Enabled", false)
                .elem("Quantity", 0)
                .end("ActiveTrustedSigners")
                .raw(xmlStreamingDistributionConfigBody(sd))
                .end("StreamingDistribution")
                .build();
    }

    private String xmlStreamingDistributionConfigBody(StreamingDistribution sd) {
        List<String> aliases = sd.getAliases() != null ? sd.getAliases() : List.of();
        XmlBuilder xml = new XmlBuilder()
                .start("StreamingDistributionConfig")
                .elem("CallerReference", sd.getCallerReference() != null ? sd.getCallerReference() : "")
                .elem("Comment", sd.getComment() != null ? sd.getComment() : "")
                .elem("Enabled", sd.isEnabled())
                .elem("PriceClass", sd.getPriceClass() != null ? sd.getPriceClass() : "PriceClass_All")
                .start("S3Origin")
                .elem("DomainName", sd.getS3Bucket() != null ? sd.getS3Bucket() : "")
                .elem("OriginAccessIdentity",
                        sd.getS3OriginAccessIdentity() != null ? sd.getS3OriginAccessIdentity() : "")
                .end("S3Origin")
                .start("Aliases").elem("Quantity", aliases.size());
        if (!aliases.isEmpty()) {
            xml.start("Items");
            for (String a : aliases) {
                xml.elem("CNAME", a);
            }
            xml.end("Items");
        }
        xml.end("Aliases")
                .start("TrustedSigners").elem("Enabled", false).elem("Quantity", 0).end("TrustedSigners")
                .end("StreamingDistributionConfig");
        return xml.build();
    }

    private String xmlFieldLevelEncryptionConfigResponse(FieldLevelEncryptionConfig cfg) {
        return new XmlBuilder()
                .start("FieldLevelEncryption")
                .elem("Id", cfg.getId())
                .elem("LastModifiedTime",
                        cfg.getLastModifiedTime() != null ? cfg.getLastModifiedTime().toString() : "")
                .start("FieldLevelEncryptionConfig")
                .elem("CallerReference", cfg.getCallerReference() != null ? cfg.getCallerReference() : "")
                .elem("Comment", cfg.getComment() != null ? cfg.getComment() : "")
                .end("FieldLevelEncryptionConfig")
                .end("FieldLevelEncryption")
                .build();
    }

    private String xmlFieldLevelEncryptionProfileResponse(FieldLevelEncryptionProfile profile) {
        return new XmlBuilder()
                .start("FieldLevelEncryptionProfile")
                .elem("Id", profile.getId())
                .elem("LastModifiedTime",
                        profile.getLastModifiedTime() != null ? profile.getLastModifiedTime().toString() : "")
                .start("FieldLevelEncryptionProfileConfig")
                .elem("Name", profile.getName() != null ? profile.getName() : "")
                .elem("CallerReference",
                        profile.getCallerReference() != null ? profile.getCallerReference() : "")
                .elem("Comment", profile.getComment() != null ? profile.getComment() : "")
                .start("EncryptionEntities").elem("Quantity", 0).end("EncryptionEntities")
                .end("FieldLevelEncryptionProfileConfig")
                .end("FieldLevelEncryptionProfile")
                .build();
    }

    private String xmlMonitoringSubscriptionResponse(MonitoringSubscription sub) {
        return new XmlBuilder()
                .start("MonitoringSubscription", NS)
                .start("RealtimeMetricsSubscriptionConfig")
                .elem("RealtimeMetricsSubscriptionStatus",
                        sub.getRealtimeMetricsSubscriptionStatus() != null
                                ? sub.getRealtimeMetricsSubscriptionStatus() : "Disabled")
                .end("RealtimeMetricsSubscriptionConfig")
                .end("MonitoringSubscription")
                .build();
    }

    // ── Phase 2 request parsers ───────────────────────────────────────────────

    private ContinuousDeploymentPolicy parseContinuousDeploymentPolicy(String body) {
        ContinuousDeploymentPolicy policy = new ContinuousDeploymentPolicy();
        policy.setEnabled("true".equalsIgnoreCase(XmlParser.extractFirst(body, "Enabled", "false")));
        policy.setStagingDistributionDnsNames(XmlParser.extractAll(body, "DnsName"));
        return policy;
    }

    private PublicKey parsePublicKey(String body) {
        PublicKey key = new PublicKey();
        key.setCallerReference(XmlParser.extractFirst(body, "CallerReference", null));
        key.setName(XmlParser.extractFirst(body, "Name", null));
        key.setEncodedKey(XmlParser.extractFirst(body, "EncodedKey", null));
        key.setComment(XmlParser.extractFirst(body, "Comment", null));
        return key;
    }

    private KeyGroup parseKeyGroup(String body) {
        KeyGroup group = new KeyGroup();
        group.setName(XmlParser.extractFirst(body, "Name", null));
        group.setComment(XmlParser.extractFirst(body, "Comment", null));
        group.setItems(XmlParser.extractAll(body, "PublicKey"));
        return group;
    }

    private RealtimeLogConfig parseRealtimeLogConfig(String body) {
        RealtimeLogConfig cfg = new RealtimeLogConfig();
        cfg.setName(XmlParser.extractFirst(body, "Name", null));
        String sr = XmlParser.extractFirst(body, "SamplingRate", "100");
        try {
            cfg.setSamplingRate(Long.parseLong(sr));
        } catch (NumberFormatException ignored) {
            cfg.setSamplingRate(100);
        }
        cfg.setFields(XmlParser.extractAll(body, "Field"));
        List<Map<String, Object>> endpoints = new ArrayList<>();
        for (Map<String, String> stream : XmlParser.extractGroups(body, "KinesisStreamConfig")) {
            Map<String, Object> endpoint = new LinkedHashMap<>();
            endpoint.put("StreamType", "Kinesis");
            endpoint.put("RoleARN", stream.get("RoleARN"));
            endpoint.put("StreamARN", stream.get("StreamARN"));
            endpoints.add(endpoint);
        }
        cfg.setEndPoints(endpoints);
        return cfg;
    }

    private StreamingDistribution parseStreamingDistribution(String body) {
        StreamingDistribution sd = new StreamingDistribution();
        sd.setCallerReference(XmlParser.extractFirst(body, "CallerReference", null));
        sd.setEnabled("true".equalsIgnoreCase(XmlParser.extractFirst(body, "Enabled", "false")));
        sd.setComment(XmlParser.extractFirst(body, "Comment", ""));
        sd.setPriceClass(XmlParser.extractFirst(body, "PriceClass", "PriceClass_All"));
        sd.setS3Bucket(XmlParser.extractFirst(body, "DomainName", null));
        sd.setS3OriginAccessIdentity(XmlParser.extractFirst(body, "OriginAccessIdentity", ""));
        sd.setAliases(XmlParser.extractAll(body, "CNAME"));
        return sd;
    }

    private FieldLevelEncryptionConfig parseFieldLevelEncryptionConfig(String body) {
        FieldLevelEncryptionConfig cfg = new FieldLevelEncryptionConfig();
        cfg.setCallerReference(XmlParser.extractFirst(body, "CallerReference", null));
        cfg.setComment(XmlParser.extractFirst(body, "Comment", null));
        return cfg;
    }

    private FieldLevelEncryptionProfile parseFieldLevelEncryptionProfile(String body) {
        FieldLevelEncryptionProfile profile = new FieldLevelEncryptionProfile();
        profile.setName(XmlParser.extractFirst(body, "Name", null));
        profile.setCallerReference(XmlParser.extractFirst(body, "CallerReference", null));
        profile.setComment(XmlParser.extractFirst(body, "Comment", null));
        return profile;
    }

    private MonitoringSubscription parseMonitoringSubscription(String body) {
        MonitoringSubscription sub = new MonitoringSubscription();
        sub.setRealtimeMetricsSubscriptionStatus(
                XmlParser.extractFirst(body, "RealtimeMetricsSubscriptionStatus", "Enabled"));
        return sub;
    }

    private Map<String, String> parseTags(String body) {
        Map<String, String> tags = new LinkedHashMap<>();
        List<Map<String, String>> groups = XmlParser.extractGroups(body, "Tag");
        for (Map<String, String> group : groups) {
            String key = group.get("Key");
            if (key != null) {
                tags.put(key, group.getOrDefault("Value", ""));
            }
        }
        return tags;
    }
}
