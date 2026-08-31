package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.s3.model.S3AccessPoint;
import io.github.hectorvent.floci.services.s3.model.S3BatchJob;
import io.github.hectorvent.floci.services.s3.model.S3MultiRegionAccessPoint;
import io.github.hectorvent.floci.services.s3.model.S3ObjectLambdaAccessPoint;
import io.github.hectorvent.floci.services.s3.model.S3StorageLensConfiguration;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import jakarta.ws.rs.core.Response;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S3 Control API endpoints used by Terraform AWS provider v6.x and other tools.
 * All endpoints are under /v20180820 matching the S3 Control API version.
 *
 * Protocol: REST-XML
 * Namespace: http://awss3control.amazonaws.com/doc/2018-08-20/
 */
@Path("/v20180820")
@Produces(MediaType.APPLICATION_XML)
public class S3ControlController {

    private static final Logger LOG = Logger.getLogger(S3ControlController.class);

    private static final String AMZ_REQUEST_ID = "x-amz-request-id";
    private static final String AMZN_REQUEST_ID = "x-amzn-RequestId";
    private static final String AMZ_ID_2 = "x-amz-id-2";

    private final S3Service s3Service;
    private final S3ControlService s3ControlService;

    @Inject
    public S3ControlController(S3Service s3Service, S3ControlService s3ControlService) {
        this.s3Service = s3Service;
        this.s3ControlService = s3ControlService;
    }

    /**
     * ListTagsForResource — returns all tags on the specified S3 bucket.
     * Used by Terraform AWS provider v6.x during bucket read-back.
     *
     * GET /v20180820/tags/{resourceArn+}
     * Header: x-amz-account-id
     */
    @GET
    @Path("/tags/{resourceArn: .+}")
    public Response listTagsForResource(
            @PathParam("resourceArn") String resourceArn,
            @HeaderParam("x-amz-account-id") String accountId) {

        try {
            String decoded = decodeArn(resourceArn);
            String accessPointName = extractAccessPointName(decoded);
            Map<String, String> tags = accessPointName != null
                    ? s3ControlService.listTags(accessPointName)
                    : s3Service.listBucketTags(extractBucketNameFromDecoded(decoded));
            return Response.ok(tagsXml(tags)).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * ListAccessPoints — returns the account's S3 access points.
     *
     * GET /v20180820/accesspoint
     * Header: x-amz-account-id
     */
    @GET
    @Path("/accesspoint")
    public Response listAccessPoints(
            @HeaderParam("x-amz-account-id") String accountId,
            @QueryParam("bucket") String bucket,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {

        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ListAccessPointsResult", AwsNamespaces.S3_CONTROL)
                .start("AccessPointList");
        for (S3AccessPoint accessPoint : s3ControlService.listAccessPoints(bucket)) {
            appendAccessPointSummary(xml, accessPoint);
        }
        xml.end("AccessPointList").end("ListAccessPointsResult");
        return Response.ok(xml.build()).build();
    }

    /**
     * GetAccessPoint — returns configuration for the named access point.
     *
     * GET /v20180820/accesspoint/{name}
     * Header: x-amz-account-id
     */
    @GET
    @Path("/accesspoint/{name}")
    public Response getAccessPoint(
            @PathParam("name") String name,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            return Response.ok(getAccessPointXml(s3ControlService.getAccessPoint(name))).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * CreateAccessPoint — attaches a named access point to a bucket.
     *
     * PUT /v20180820/accesspoint/{name}
     * Header: x-amz-account-id
     */
    @PUT
    @Path("/accesspoint/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response createAccessPoint(
            @PathParam("name") String name,
            @HeaderParam("x-amz-account-id") String accountId,
            byte[] body) {
        try {
            String xml = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            S3AccessPoint created = s3ControlService.createAccessPoint(
                    accountId,
                    name,
                    XmlParser.extractFirst(xml, "Bucket", null),
                    XmlParser.extractFirst(xml, "BucketAccountId", null),
                    XmlParser.extractFirst(xml, "VpcId", null),
                    xmlBool(xml, "BlockPublicAcls"),
                    xmlBool(xml, "IgnorePublicAcls"),
                    xmlBool(xml, "BlockPublicPolicy"),
                    xmlBool(xml, "RestrictPublicBuckets"),
                    XmlParser.extractPairs(xml, "Tag", "Key", "Value"));
            String response = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("CreateAccessPointResult", AwsNamespaces.S3_CONTROL)
                    .elem("AccessPointArn", created.getArn())
                    .elem("Alias", created.getAlias())
                    .end("CreateAccessPointResult")
                    .build();
            return Response.ok(response).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * DeleteAccessPoint — deletes the named access point.
     *
     * DELETE /v20180820/accesspoint/{name}
     * Header: x-amz-account-id
     */
    @DELETE
    @Path("/accesspoint/{name}")
    public Response deleteAccessPoint(
            @PathParam("name") String name,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            s3ControlService.deleteAccessPoint(name);
            return Response.ok().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * GetAccessPointPolicy — returns the resource policy attached to the access point.
     *
     * GET /v20180820/accesspoint/{name}/policy
     * Header: x-amz-account-id
     */
    @GET
    @Path("/accesspoint/{name}/policy")
    public Response getAccessPointPolicy(
            @PathParam("name") String name,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            String policy = s3ControlService.getAccessPointPolicy(name);
            String xml = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("GetAccessPointPolicyResult", AwsNamespaces.S3_CONTROL)
                    .elem("Policy", policy)
                    .end("GetAccessPointPolicyResult")
                    .build();
            return Response.ok(xml).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * PutAccessPointPolicy — creates or replaces the access point resource policy.
     *
     * PUT /v20180820/accesspoint/{name}/policy
     * Header: x-amz-account-id
     */
    @PUT
    @Path("/accesspoint/{name}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response putAccessPointPolicy(
            @PathParam("name") String name,
            @HeaderParam("x-amz-account-id") String accountId,
            byte[] body) {
        try {
            String xml = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            String policy = extractPolicyDocument(xml);
            s3ControlService.putAccessPointPolicy(name, policy);
            return Response.ok().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * DeleteAccessPointPolicy — removes the access point resource policy.
     *
     * DELETE /v20180820/accesspoint/{name}/policy
     * Header: x-amz-account-id
     */
    @DELETE
    @Path("/accesspoint/{name}/policy")
    public Response deleteAccessPointPolicy(
            @PathParam("name") String name,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            s3ControlService.deleteAccessPointPolicy(name);
            return Response.ok().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * GetAccessPointPolicyStatus — whether the access point policy currently grants public access.
     *
     * GET /v20180820/accesspoint/{name}/policyStatus
     * Header: x-amz-account-id
     */
    @GET
    @Path("/accesspoint/{name}/policyStatus")
    public Response getAccessPointPolicyStatus(
            @PathParam("name") String name,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            boolean isPublic = s3ControlService.getAccessPointPolicyStatus(name);
            String xml = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("GetAccessPointPolicyStatusResult", AwsNamespaces.S3_CONTROL)
                    .start("PolicyStatus")
                    .elem("IsPublic", isPublic)
                    .end("PolicyStatus")
                    .end("GetAccessPointPolicyStatusResult")
                    .build();
            return Response.ok(xml).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * ListAccessPointsForObjectLambda — GET /v20180820/accesspointforobjectlambda
     */
    @GET
    @Path("/accesspointforobjectlambda")
    public Response listAccessPointsForObjectLambda(
            @HeaderParam("x-amz-account-id") String accountId,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ListAccessPointsForObjectLambdaResult", AwsNamespaces.S3_CONTROL)
                .start("ObjectLambdaAccessPointList");
        for (S3ObjectLambdaAccessPoint olap : s3ControlService.listObjectLambdaAccessPoints()) {
            xml.start("ObjectLambdaAccessPoint")
                    .elem("Name", olap.getName())
                    .elem("ObjectLambdaAccessPointArn", olap.getArn());
            appendObjectLambdaAlias(xml, olap);
            xml.end("ObjectLambdaAccessPoint");
        }
        xml.end("ObjectLambdaAccessPointList").end("ListAccessPointsForObjectLambdaResult");
        return Response.ok(xml.build()).build();
    }

    /**
     * GetAccessPointForObjectLambda — GET /v20180820/accesspointforobjectlambda/{name}
     */
    @GET
    @Path("/accesspointforobjectlambda/{name}")
    public Response getAccessPointForObjectLambda(
            @PathParam("name") String name,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            return Response.ok(getObjectLambdaAccessPointXml(
                    s3ControlService.getObjectLambdaAccessPoint(name))).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * CreateAccessPointForObjectLambda — PUT /v20180820/accesspointforobjectlambda/{name}
     */
    @PUT
    @Path("/accesspointforobjectlambda/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response createAccessPointForObjectLambda(
            @PathParam("name") String name,
            @HeaderParam("x-amz-account-id") String accountId,
            byte[] body) {
        try {
            String xml = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            S3ObjectLambdaAccessPoint created =
                    s3ControlService.createObjectLambdaAccessPoint(accountId, name, xml);
            XmlBuilder response = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("CreateAccessPointForObjectLambdaResult", AwsNamespaces.S3_CONTROL)
                    .elem("ObjectLambdaAccessPointArn", created.getArn());
            appendObjectLambdaAlias(response, created);
            response.end("CreateAccessPointForObjectLambdaResult");
            return Response.ok(response.build()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * DeleteAccessPointForObjectLambda — DELETE /v20180820/accesspointforobjectlambda/{name}
     */
    @DELETE
    @Path("/accesspointforobjectlambda/{name}")
    public Response deleteAccessPointForObjectLambda(
            @PathParam("name") String name,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            s3ControlService.deleteObjectLambdaAccessPoint(name);
            return Response.ok().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * GetAccessPointConfigurationForObjectLambda —
     * GET /v20180820/accesspointforobjectlambda/{name}/configuration
     */
    @GET
    @Path("/accesspointforobjectlambda/{name}/configuration")
    public Response getAccessPointConfigurationForObjectLambda(
            @PathParam("name") String name,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            return Response.ok(objectLambdaConfigurationXml(
                    s3ControlService.getObjectLambdaAccessPoint(name))).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * PutAccessPointConfigurationForObjectLambda —
     * PUT /v20180820/accesspointforobjectlambda/{name}/configuration
     */
    @PUT
    @Path("/accesspointforobjectlambda/{name}/configuration")
    @Consumes(MediaType.WILDCARD)
    public Response putAccessPointConfigurationForObjectLambda(
            @PathParam("name") String name,
            @HeaderParam("x-amz-account-id") String accountId,
            byte[] body) {
        try {
            String xml = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            s3ControlService.putObjectLambdaConfiguration(name, xml);
            return Response.ok().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * TagResource — replaces all tags on the specified S3 bucket.
     *
     * POST /v20180820/tags/{resourceArn+}
     * Header: x-amz-account-id
     * Body: XML containing {@code <Tags><Tag><Key>…</Key><Value>…</Value></Tag></Tags>}
     */
    @POST
    @Path("/tags/{resourceArn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response tagResource(
            @PathParam("resourceArn") String resourceArn,
            @HeaderParam("x-amz-account-id") String accountId,
            byte[] body) {

        try {
            String decoded = decodeArn(resourceArn);
            String accessPointName = extractAccessPointName(decoded);
            String xml = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            Map<String, String> tags = XmlParser.extractPairs(xml, "Tag", "Key", "Value");
            if (accessPointName != null) {
                s3ControlService.tagAccessPoint(accessPointName, tags);
            } else {
                s3Service.putBucketTagging(extractBucketNameFromDecoded(decoded), tags);
            }
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * UntagResource — removes specific tags from the specified S3 bucket.
     *
     * DELETE /v20180820/tags/{resourceArn+}?tagKeys=Key1&tagKeys=Key2
     * Header: x-amz-account-id
     */
    @DELETE
    @Path("/tags/{resourceArn: .+}")
    public Response untagResource(
            @PathParam("resourceArn") String resourceArn,
            @HeaderParam("x-amz-account-id") String accountId,
            @QueryParam("tagKeys") List<String> tagKeys) {

        try {
            String decoded = decodeArn(resourceArn);
            String accessPointName = extractAccessPointName(decoded);
            if (accessPointName != null) {
                s3ControlService.untagAccessPoint(accessPointName, tagKeys);
            } else {
                String bucketName = extractBucketNameFromDecoded(decoded);
                Map<String, String> existing = new HashMap<>(s3Service.listBucketTags(bucketName));
                if (tagKeys != null) {
                    tagKeys.forEach(existing::remove);
                }
                s3Service.putBucketTagging(bucketName, existing);
            }
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * CreateJob — S3 Batch Operations. ConfirmationRequired jobs settle in Suspended;
     * others complete immediately (no worker fleet).
     *
     * POST /v20180820/jobs
     * Header: x-amz-account-id
     */
    @POST
    @Path("/jobs")
    @Consumes(MediaType.WILDCARD)
    public Response createJob(
            @HeaderParam("x-amz-account-id") String accountId,
            byte[] body) {
        try {
            String xml = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            S3BatchJob created = s3ControlService.createJob(
                    accountId,
                    XmlParser.extractFirst(xml, "ClientRequestToken", null),
                    xmlBool(xml, "ConfirmationRequired"),
                    xmlInt(xml, "Priority"),
                    XmlParser.extractFirst(xml, "RoleArn", null),
                    detectJobOperation(xml),
                    XmlParser.extractFirst(xml, "Description", null),
                    Boolean.TRUE.equals(xmlBool(xml, "Enabled")),
                    XmlParser.extractFirst(xml, "SourceBucket", null));
            String response = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("CreateJobResult", AwsNamespaces.S3_CONTROL)
                    .elem("JobId", created.getJobId())
                    .end("CreateJobResult")
                    .build();
            return Response.ok(response).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * ListJobs — account-level S3 Batch Operations listing.
     *
     * GET /v20180820/jobs?jobStatuses=...
     * Header: x-amz-account-id
     */
    @GET
    @Path("/jobs")
    public Response listJobs(
            @HeaderParam("x-amz-account-id") String accountId,
            @QueryParam("jobStatuses") List<String> jobStatuses,
            @QueryParam("nextToken") String nextToken,
            @QueryParam("maxResults") Integer maxResults) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ListJobsResult", AwsNamespaces.S3_CONTROL)
                .start("Jobs");
        for (S3BatchJob job : s3ControlService.listJobs(jobStatuses)) {
            xml.start("member");
            appendJobListDescriptor(xml, job);
            xml.end("member");
        }
        xml.end("Jobs").end("ListJobsResult");
        return Response.ok(xml.build()).build();
    }

    /**
     * DescribeJob — returns the Batch Operations job descriptor.
     *
     * GET /v20180820/jobs/{jobId}
     * Header: x-amz-account-id
     */
    @GET
    @Path("/jobs/{jobId}")
    public Response describeJob(
            @PathParam("jobId") String jobId,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            return Response.ok(describeJobXml(s3ControlService.describeJob(jobId))).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * UpdateJobPriority — POST /v20180820/jobs/{jobId}/priority?priority=N
     */
    @POST
    @Path("/jobs/{jobId}/priority")
    public Response updateJobPriority(
            @PathParam("jobId") String jobId,
            @HeaderParam("x-amz-account-id") String accountId,
            @QueryParam("priority") Integer priority) {
        try {
            S3BatchJob job = s3ControlService.updateJobPriority(jobId, priority);
            String xml = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("UpdateJobPriorityResult", AwsNamespaces.S3_CONTROL)
                    .elem("JobId", job.getJobId())
                    .elem("Priority", job.getPriority())
                    .end("UpdateJobPriorityResult")
                    .build();
            return Response.ok(xml).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * UpdateJobStatus — POST /v20180820/jobs/{jobId}/status?requestedJobStatus=Cancelled
     */
    @POST
    @Path("/jobs/{jobId}/status")
    public Response updateJobStatus(
            @PathParam("jobId") String jobId,
            @HeaderParam("x-amz-account-id") String accountId,
            @QueryParam("requestedJobStatus") String requestedJobStatus,
            @QueryParam("statusUpdateReason") String statusUpdateReason) {
        try {
            S3BatchJob job = s3ControlService.updateJobStatus(jobId, requestedJobStatus, statusUpdateReason);
            String xml = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("UpdateJobStatusResult", AwsNamespaces.S3_CONTROL)
                    .elem("JobId", job.getJobId())
                    .elem("Status", job.getStatus())
                    .elem("StatusUpdateReason", job.getStatusUpdateReason())
                    .end("UpdateJobStatusResult")
                    .build();
            return Response.ok(xml).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * ListStorageLensConfigurations — GET /v20180820/storagelens
     */
    @GET
    @Path("/storagelens")
    public Response listStorageLensConfigurations(
            @HeaderParam("x-amz-account-id") String accountId,
            @QueryParam("nextToken") String nextToken) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ListStorageLensConfigurationResult", AwsNamespaces.S3_CONTROL);
        for (S3StorageLensConfiguration config : s3ControlService.listStorageLensConfigurations()) {
            xml.start("StorageLensConfiguration")
                    .elem("Id", config.getConfigId())
                    .elem("StorageLensArn", config.getArn())
                    .elem("HomeRegion", config.getRegion())
                    .elem("IsEnabled", config.isEnabled())
                    .end("StorageLensConfiguration");
        }
        xml.end("ListStorageLensConfigurationResult");
        return Response.ok(xml.build()).build();
    }

    /**
     * GetStorageLensConfiguration — GET /v20180820/storagelens/{ConfigId}
     */
    @GET
    @Path("/storagelens/{configId}")
    public Response getStorageLensConfiguration(
            @PathParam("configId") String configId,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            return Response.ok(S3StorageLensXml.toGetXml(
                    s3ControlService.getStorageLensConfiguration(configId))).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * PutStorageLensConfiguration — PUT /v20180820/storagelens/{ConfigId}
     */
    @PUT
    @Path("/storagelens/{configId}")
    @Consumes(MediaType.WILDCARD)
    public Response putStorageLensConfiguration(
            @PathParam("configId") String configId,
            @HeaderParam("x-amz-account-id") String accountId,
            byte[] body) {
        try {
            String xml = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            s3ControlService.putStorageLensConfiguration(accountId, configId, xml);
            return Response.ok().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * DeleteStorageLensConfiguration — DELETE /v20180820/storagelens/{ConfigId}
     */
    @DELETE
    @Path("/storagelens/{configId}")
    public Response deleteStorageLensConfiguration(
            @PathParam("configId") String configId,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            s3ControlService.deleteStorageLensConfiguration(configId);
            return Response.ok().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * GetStorageLensConfigurationTagging — GET /v20180820/storagelens/{ConfigId}/tagging
     */
    @GET
    @Path("/storagelens/{configId}/tagging")
    public Response getStorageLensConfigurationTagging(
            @PathParam("configId") String configId,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            return Response.ok(S3StorageLensXml.toTagsXml(
                    s3ControlService.getStorageLensTags(configId))).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * PutStorageLensConfigurationTagging — PUT /v20180820/storagelens/{ConfigId}/tagging
     */
    @PUT
    @Path("/storagelens/{configId}/tagging")
    @Consumes(MediaType.WILDCARD)
    public Response putStorageLensConfigurationTagging(
            @PathParam("configId") String configId,
            @HeaderParam("x-amz-account-id") String accountId,
            byte[] body) {
        try {
            String xml = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            s3ControlService.putStorageLensTags(configId, S3StorageLensXml.tagsFrom(xml));
            return Response.ok().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * DeleteStorageLensConfigurationTagging — DELETE /v20180820/storagelens/{ConfigId}/tagging
     */
    @DELETE
    @Path("/storagelens/{configId}/tagging")
    public Response deleteStorageLensConfigurationTagging(
            @PathParam("configId") String configId,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            s3ControlService.deleteStorageLensTags(configId);
            return Response.ok().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * ListMultiRegionAccessPoints — GET /v20180820/mrap/instances
     */
    @GET
    @Path("/mrap/instances")
    public Response listMultiRegionAccessPoints(
            @HeaderParam("x-amz-account-id") String accountId,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ListMultiRegionAccessPointsResult", AwsNamespaces.S3_CONTROL)
                .start("AccessPoints");
        for (S3MultiRegionAccessPoint mrap : s3ControlService.listMultiRegionAccessPoints()) {
            appendMrapReport(xml, mrap);
        }
        xml.end("AccessPoints").end("ListMultiRegionAccessPointsResult");
        return Response.ok(xml.build()).build();
    }

    /**
     * GetMultiRegionAccessPoint — GET /v20180820/mrap/instances/{Name+}
     */
    @GET
    @Path("/mrap/instances/{name:.+}")
    public Response getMultiRegionAccessPoint(
            @PathParam("name") String name,
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            S3MultiRegionAccessPoint mrap = s3ControlService.getMultiRegionAccessPoint(name);
            XmlBuilder xml = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("GetMultiRegionAccessPointResult", AwsNamespaces.S3_CONTROL);
            appendMrapReport(xml, mrap);
            xml.end("GetMultiRegionAccessPointResult");
            return Response.ok(xml.build()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * CreateMultiRegionAccessPoint — POST /v20180820/async-requests/mrap/create
     */
    @POST
    @Path("/async-requests/mrap/create")
    @Consumes(MediaType.WILDCARD)
    public Response createMultiRegionAccessPoint(
            @HeaderParam("x-amz-account-id") String accountId,
            byte[] body) {
        try {
            String xml = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            S3MultiRegionAccessPoint created = s3ControlService.createMultiRegionAccessPoint(
                    accountId,
                    XmlParser.extractFirst(xml, "Name", null),
                    parseMrapRegions(xml),
                    xmlBool(xml, "BlockPublicAcls"),
                    xmlBool(xml, "IgnorePublicAcls"),
                    xmlBool(xml, "BlockPublicPolicy"),
                    xmlBool(xml, "RestrictPublicBuckets"));
            return Response.ok(asyncRequestTokenXml("CreateMultiRegionAccessPointResult",
                    created.getRequestTokenArn())).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * DeleteMultiRegionAccessPoint — POST /v20180820/async-requests/mrap/delete
     */
    @POST
    @Path("/async-requests/mrap/delete")
    @Consumes(MediaType.WILDCARD)
    public Response deleteMultiRegionAccessPoint(
            @HeaderParam("x-amz-account-id") String accountId,
            byte[] body) {
        try {
            String xml = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            String token = s3ControlService.deleteMultiRegionAccessPoint(
                    accountId, XmlParser.extractFirst(xml, "Name", null));
            return Response.ok(asyncRequestTokenXml("DeleteMultiRegionAccessPointResult", token)).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * Parse the bucket name out of an S3 bucket ARN path parameter.
     *
     * <p>The AWS Go SDK v2 (used by Terraform) percent-encodes the ARN's
     * colons and slashes in the request path, while the Java SDK sends them
     * literally. We decode defensively so both forms work, and so routing
     * frameworks that leave {@code %2F} encoded in path segments don't break
     * us.
     *
     * <p>Two valid ARN forms are accepted:
     * <ul>
     *   <li>S3 Control ARN: {@code arn:aws:s3:<region>:<account>:bucket/<name>}</li>
     *   <li>Plain S3 ARN:   {@code arn:aws:s3:::<name>} — sent by Go SDK v2 / Terraform provider v6</li>
     * </ul>
     */
    private String extractBucketName(String resourceArn) {
        return extractBucketNameFromDecoded(decodeArn(resourceArn));
    }

    private String decodeArn(String resourceArn) {
        try {
            return URLDecoder.decode(resourceArn, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidRequest",
                    "Malformed percent-encoding in resource ARN: " + e.getMessage(), 400);
        }
    }

    private String extractAccessPointName(String decodedArn) {
        int idx = decodedArn.lastIndexOf(":accesspoint/");
        if (idx < 0) {
            return null;
        }
        String name = decodedArn.substring(idx + ":accesspoint/".length());
        int slash = name.indexOf('/');
        return slash >= 0 ? name.substring(0, slash) : name;
    }

    private String extractBucketNameFromDecoded(String decoded) {
        // Form 1: arn:<partition>:s3:<region>:<account>:bucket/<name>
        int idx = decoded.lastIndexOf(":bucket/");
        if (idx >= 0) {
            return decoded.substring(idx + ":bucket/".length());
        }

        // Form 2: arn:<partition>:s3:::<name>  (plain S3 ARN — no region, no account)
        // Go SDK v2 / Terraform provider v6 sends this form for general-purpose buckets.
        String[] parts = decoded.split(":", 6);
        if (parts.length == 6 && "s3".equals(parts[2])
                && parts[3].isEmpty() && parts[4].isEmpty()
                && !parts[5].isEmpty() && !parts[5].contains("/")) {
            return parts[5];
        }

        throw new AwsException("InvalidRequest",
                "Unsupported resource type. Only S3 bucket ARNs are supported " +
                "(arn:aws:s3:<region>:<account>:bucket/<name> or arn:aws:s3:::<name>).", 400);
    }

    private String tagsXml(Map<String, String> tags) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ListTagsForResourceResult", AwsNamespaces.S3_CONTROL)
                .start("Tags");
        tags.forEach((k, v) ->
                xml.start("Tag").elem("Key", k).elem("Value", v).end("Tag"));
        xml.end("Tags").end("ListTagsForResourceResult");
        return xml.build();
    }

    private String getAccessPointXml(S3AccessPoint accessPoint) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("GetAccessPointResult", AwsNamespaces.S3_CONTROL)
                .elem("Name", accessPoint.getName())
                .elem("Bucket", accessPoint.getBucket())
                .elem("NetworkOrigin", accessPoint.getNetworkOrigin());
        if (accessPoint.getVpcId() != null && !accessPoint.getVpcId().isBlank()) {
            xml.start("VpcConfiguration")
                    .elem("VpcId", accessPoint.getVpcId())
                    .end("VpcConfiguration");
        }
        xml.start("PublicAccessBlockConfiguration")
                .elem("BlockPublicAcls", accessPoint.isBlockPublicAcls())
                .elem("IgnorePublicAcls", accessPoint.isIgnorePublicAcls())
                .elem("BlockPublicPolicy", accessPoint.isBlockPublicPolicy())
                .elem("RestrictPublicBuckets", accessPoint.isRestrictPublicBuckets())
                .end("PublicAccessBlockConfiguration")
                .elem("CreationDate", accessPoint.getCreationDate() == null
                        ? null : accessPoint.getCreationDate().toString())
                .elem("Alias", accessPoint.getAlias())
                .elem("AccessPointArn", accessPoint.getArn())
                .elem("BucketAccountId", accessPoint.getBucketAccountId())
                .end("GetAccessPointResult");
        return xml.build();
    }

    private String getObjectLambdaAccessPointXml(S3ObjectLambdaAccessPoint olap) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("GetAccessPointForObjectLambdaResult", AwsNamespaces.S3_CONTROL)
                .elem("Name", olap.getName())
                .start("PublicAccessBlockConfiguration")
                .elem("BlockPublicAcls", olap.isBlockPublicAcls())
                .elem("IgnorePublicAcls", olap.isIgnorePublicAcls())
                .elem("BlockPublicPolicy", olap.isBlockPublicPolicy())
                .elem("RestrictPublicBuckets", olap.isRestrictPublicBuckets())
                .end("PublicAccessBlockConfiguration")
                .elem("CreationDate", olap.getCreationDate() == null
                        ? null : olap.getCreationDate().toString());
        appendObjectLambdaAlias(xml, olap);
        xml.end("GetAccessPointForObjectLambdaResult");
        return xml.build();
    }

    private String objectLambdaConfigurationXml(S3ObjectLambdaAccessPoint olap) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("GetAccessPointConfigurationForObjectLambdaResult", AwsNamespaces.S3_CONTROL)
                .start("Configuration")
                .elem("SupportingAccessPoint", olap.getSupportingAccessPoint())
                .elem("CloudWatchMetricsEnabled", olap.isCloudWatchMetricsEnabled());
        if (olap.getAllowedFeatures() != null && !olap.getAllowedFeatures().isEmpty()) {
            xml.start("AllowedFeatures");
            for (String feature : olap.getAllowedFeatures()) {
                xml.elem("AllowedFeature", feature);
            }
            xml.end("AllowedFeatures");
        }
        xml.start("TransformationConfigurations");
        for (S3ObjectLambdaAccessPoint.Transformation transformation
                : olap.getTransformationConfigurations()) {
            xml.start("TransformationConfiguration").start("Actions");
            for (String action : transformation.getActions()) {
                xml.elem("Action", action);
            }
            xml.end("Actions")
                    .start("ContentTransformation")
                    .start("AwsLambda")
                    .elem("FunctionArn", transformation.getFunctionArn())
                    .elem("FunctionPayload", transformation.getFunctionPayload())
                    .end("AwsLambda")
                    .end("ContentTransformation")
                    .end("TransformationConfiguration");
        }
        xml.end("TransformationConfigurations")
                .end("Configuration")
                .end("GetAccessPointConfigurationForObjectLambdaResult");
        return xml.build();
    }

    private static void appendObjectLambdaAlias(XmlBuilder xml, S3ObjectLambdaAccessPoint olap) {
        xml.start("Alias")
                .elem("Value", olap.getAlias())
                .elem("Status", olap.getAliasStatus() == null ? "READY" : olap.getAliasStatus())
                .end("Alias");
    }

    private void appendAccessPointSummary(XmlBuilder xml, S3AccessPoint accessPoint) {
        xml.start("AccessPoint")
                .elem("Name", accessPoint.getName())
                .elem("NetworkOrigin", accessPoint.getNetworkOrigin());
        if (accessPoint.getVpcId() != null && !accessPoint.getVpcId().isBlank()) {
            xml.start("VpcConfiguration")
                    .elem("VpcId", accessPoint.getVpcId())
                    .end("VpcConfiguration");
        }
        xml.elem("Bucket", accessPoint.getBucket())
                .elem("AccessPointArn", accessPoint.getArn())
                .elem("Alias", accessPoint.getAlias())
                .elem("BucketAccountId", accessPoint.getBucketAccountId())
                .end("AccessPoint");
    }

    private void appendMrapReport(XmlBuilder xml, S3MultiRegionAccessPoint mrap) {
        xml.start("AccessPoint")
                .elem("Name", mrap.getName())
                .elem("Alias", mrap.getAlias())
                .elem("CreatedAt", mrap.getCreatedAt() == null ? null : mrap.getCreatedAt().toString())
                .start("PublicAccessBlock")
                .elem("BlockPublicAcls", mrap.isBlockPublicAcls())
                .elem("IgnorePublicAcls", mrap.isIgnorePublicAcls())
                .elem("BlockPublicPolicy", mrap.isBlockPublicPolicy())
                .elem("RestrictPublicBuckets", mrap.isRestrictPublicBuckets())
                .end("PublicAccessBlock")
                .elem("Status", mrap.getStatus())
                .start("Regions");
        for (S3MultiRegionAccessPoint.Region region : mrap.getRegions()) {
            xml.start("Region")
                    .elem("Bucket", region.getBucket())
                    .elem("Region", region.getRegion())
                    .elem("BucketAccountId", region.getBucketAccountId())
                    .end("Region");
        }
        xml.end("Regions").end("AccessPoint");
    }

    private static List<S3MultiRegionAccessPoint.Region> parseMrapRegions(String xml) {
        List<S3MultiRegionAccessPoint.Region> regions = new ArrayList<>();
        for (Map<String, String> group : XmlParser.extractGroups(xml, "Region")) {
            String bucket = group.get("Bucket");
            if (bucket == null || bucket.isBlank()) {
                continue;
            }
            S3MultiRegionAccessPoint.Region region = new S3MultiRegionAccessPoint.Region();
            region.setBucket(bucket);
            region.setBucketAccountId(group.get("BucketAccountId"));
            regions.add(region);
        }
        return regions;
    }

    private static String asyncRequestTokenXml(String resultElement, String requestTokenArn) {
        return new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start(resultElement, AwsNamespaces.S3_CONTROL)
                .elem("RequestTokenARN", requestTokenArn)
                .end(resultElement)
                .build();
    }

    private static Boolean xmlBool(String xml, String elementName) {
        String value = XmlParser.extractFirst(xml, elementName, null);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static Integer xmlInt(String xml, String elementName) {
        String value = XmlParser.extractFirst(xml, elementName, null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidRequest",
                    elementName + " must be an integer.", 400);
        }
    }

    private static String detectJobOperation(String xml) {
        String[] operations = {
                "S3PutObjectTagging",
                "S3PutObjectCopy",
                "S3PutObjectAcl",
                "S3DeleteObjectTagging",
                "S3InitiateRestoreObject",
                "S3PutObjectLegalHold",
                "S3PutObjectRetention",
                "S3ReplicateObject",
                "S3ComputeObjectChecksum",
                "S3UpdateObjectEncryption",
                "LambdaInvoke"
        };
        for (String operation : operations) {
            if (XmlParser.extractFirst(xml, operation, null) != null
                    || xml.contains("<" + operation)
                    || xml.contains(":" + operation)) {
                return operation;
            }
        }
        return null;
    }

    private void appendJobListDescriptor(XmlBuilder xml, S3BatchJob job) {
        xml.elem("JobId", job.getJobId())
                .elem("Description", job.getDescription())
                .elem("Operation", job.getOperation())
                .elem("Priority", job.getPriority())
                .elem("Status", job.getStatus())
                .elem("CreationTime", instantXml(job.getCreationTime()))
                .elem("TerminationDate", instantXml(job.getTerminationDate()));
    }

    private String describeJobXml(S3BatchJob job) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("DescribeJobResult", AwsNamespaces.S3_CONTROL)
                .start("Job")
                .elem("JobId", job.getJobId())
                .elem("ConfirmationRequired", job.isConfirmationRequired())
                .elem("Description", job.getDescription())
                .elem("JobArn", job.getJobArn())
                .elem("Status", job.getStatus())
                .elem("Priority", job.getPriority())
                .elem("StatusUpdateReason", job.getStatusUpdateReason())
                .elem("CreationTime", instantXml(job.getCreationTime()))
                .elem("TerminationDate", instantXml(job.getTerminationDate()))
                .elem("RoleArn", job.getRoleArn())
                .elem("SuspendedDate", instantXml(job.getSuspendedDate()))
                .elem("SuspendedCause", job.getSuspendedCause());
        if (job.getOperation() != null && !job.getOperation().isBlank()) {
            xml.start("Operation").start(job.getOperation()).end(job.getOperation()).end("Operation");
        }
        xml.start("Report")
                .elem("Enabled", job.isReportEnabled())
                .end("Report")
                .end("Job")
                .end("DescribeJobResult");
        return xml.build();
    }

    private static String instantXml(java.time.Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /**
     * Pull the policy JSON out of a PutAccessPointPolicy body. Distilled / the
     * AWS SDKs wrap it in {@code <Policy>}; a raw JSON body is accepted as a
     * convenience for hand-rolled clients.
     */
    private static String extractPolicyDocument(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String fromXml = XmlParser.extractFirst(trimmed, "Policy", null);
        if (fromXml != null && !fromXml.isBlank()) {
            return fromXml.trim();
        }
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        return null;
    }

    /**
     * S3 Control is a REST-XML protocol, so error responses must also be XML.
     * AWS S3 Control wraps errors in an {@code <ErrorResponse xmlns=...>} envelope
     * containing the inner {@code <Error>} block and a top-level {@code <RequestId>}.
     *
     * <p>References: AWS Go SDK v2 s3control error deserializer expects this wrapper;
     * bare {@code <Error>} collapses to "UnknownError" at the SDK layer.
     * See issue #557.
     */
    // Same protocol safety net as S3Controller: unhandled failures on S3 Control routes
    // must render the REST-XML error contract, not Quarkus's plain-text error page.
    @ServerExceptionMapper
    public Response mapUnhandledThrowable(Throwable t) {
        LOG.error("Unhandled exception processing S3 Control request", t);
        return xmlErrorResponse(new AwsException("InternalError",
                "We encountered an internal error. Please try again.", 500));
    }

    @ServerExceptionMapper
    public Response mapEscapedAwsException(AwsException e) {
        return xmlErrorResponse(e);
    }

    private Response xmlErrorResponse(AwsException e) {
        String requestId = UUID.randomUUID().toString();
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ErrorResponse", AwsNamespaces.S3_CONTROL)
                .start("Error")
                .elem("Code", e.getErrorCode())
                .elem("Message", e.getMessage())
                .elem("RequestId", requestId)
                .end("Error")
                .elem("RequestId", requestId)
                .end("ErrorResponse")
                .build();
        return Response.status(e.getHttpStatus())
                .type(MediaType.APPLICATION_XML)
                .header(AMZ_REQUEST_ID, requestId)
                .header(AMZN_REQUEST_ID, requestId)
                .header(AMZ_ID_2, requestId)
                .entity(xml)
                .build();
    }
}
