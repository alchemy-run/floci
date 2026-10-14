package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.IamActionRegistry;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * The IAM action S3 authorizes an object request as. A request naming a {@code versionId} uses
 * the {@code *Version*} variant of its action (s3:GetObjectVersion, s3:GetObjectVersionTagging,
 * ...); object subresources resolve to their own actions rather than to s3:GetObject/PutObject.
 */
class S3VersionedIamActionResolutionTest {

    private final IamActionRegistry registry = new IamActionRegistry();

    @Test
    void versionedObjectReadsAndDeletesUseTheVersionActions() {
        assertEquals("s3:GetObject", resolve("GET", params()));
        assertEquals("s3:GetObjectVersion", resolve("GET", params("versionId", "v1")));
        assertEquals("s3:GetObjectVersion", resolve("HEAD", params("versionId", "v1")));
        assertEquals("s3:DeleteObject", resolve("DELETE", params()));
        assertEquals("s3:DeleteObjectVersion", resolve("DELETE", params("versionId", "v1")));
    }

    @Test
    void versionedTaggingAndAclUseTheVersionActions() {
        assertEquals("s3:GetObjectTagging", resolve("GET", params("tagging", "")));
        assertEquals("s3:GetObjectVersionTagging", resolve("GET", params("tagging", "", "versionId", "v1")));
        assertEquals("s3:PutObjectVersionTagging", resolve("PUT", params("tagging", "", "versionId", "v1")));
        assertEquals("s3:DeleteObjectVersionTagging", resolve("DELETE", params("tagging", "", "versionId", "v1")));
        assertEquals("s3:GetObjectVersionAcl", resolve("GET", params("acl", "", "versionId", "v1")));
        assertEquals("s3:PutObjectVersionAcl", resolve("PUT", params("acl", "", "versionId", "v1")));
    }

    @Test
    void objectSubresourcesResolveToTheirOwnActions() {
        assertEquals("s3:GetObjectAttributes", resolve("GET", params("attributes", "")));
        assertEquals("s3:GetObjectVersionAttributes", resolve("GET", params("attributes", "", "versionId", "v1")));
        assertEquals("s3:GetObjectRetention", resolve("GET", params("retention", "", "versionId", "v1")));
        assertEquals("s3:PutObjectRetention", resolve("PUT", params("retention", "")));
        assertEquals("s3:GetObjectLegalHold", resolve("GET", params("legal-hold", "")));
        assertEquals("s3:PutObjectLegalHold", resolve("PUT", params("legal-hold", "", "versionId", "v1")));
        assertEquals("s3:ListMultipartUploadParts", resolve("GET", params("uploadId", "u1")));
        assertEquals("s3:AbortMultipartUpload", resolve("DELETE", params("uploadId", "u1")));
        assertEquals("s3:PutObject", resolve("PUT", params("uploadId", "u1", "partNumber", "1")));
    }

    private String resolve(String method, MultivaluedMap<String, String> query) {
        ContainerRequestContext ctx = Mockito.mock(ContainerRequestContext.class);
        UriInfo uriInfo = Mockito.mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(query);
        when(uriInfo.getPath()).thenReturn("/bucket/folder/key.txt");
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(ctx.getMethod()).thenReturn(method);
        when(ctx.getEntityStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        return registry.resolve("s3", ctx);
    }

    private static MultivaluedMap<String, String> params(String... pairs) {
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            query.add(pairs[i], pairs[i + 1]);
        }
        return query;
    }
}
