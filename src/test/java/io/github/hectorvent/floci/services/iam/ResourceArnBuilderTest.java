package io.github.hectorvent.floci.services.iam;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceArnBuilderTest {

    private final ResourceArnBuilder builder = new ResourceArnBuilder();

    @Test
    void sesIdentityArnFromJsonFromEmailAddress() {
        String body = "{\"FromEmailAddress\":\"noreply@bound.example.com\",\"Content\":{}}";
        ContainerRequestContext ctx = mockJsonCtx("/v2/email/outbound-emails", body);

        String arn = builder.build("ses", ctx, "us-east-1", "000000000000");

        assertEquals("arn:aws:ses:us-east-1:000000000000:identity/noreply@bound.example.com", arn);
    }

    @Test
    void sesIdentityArnStripsDisplayName() {
        String body = "{\"FromEmailAddress\":\"Ada <ada@bound.example.com>\"}";
        ContainerRequestContext ctx = mockJsonCtx("/v2/email/outbound-emails", body);

        String arn = builder.build("ses", ctx, "us-east-1", "000000000000");

        assertEquals("arn:aws:ses:us-east-1:000000000000:identity/ada@bound.example.com", arn);
    }

    @Test
    void sesIdentityArnRestoresEntityStream() throws Exception {
        String body = "{\"FromEmailAddress\":\"a@b.test\"}";
        AtomicReference<InputStream> stream = new AtomicReference<>(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        ContainerRequestContext ctx = mockJsonCtx("/v2/email/outbound-emails", stream);

        builder.build("ses", ctx, "us-east-1", "000000000000");

        assertEquals(body, new String(stream.get().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void extractEmailUnwrapsAngleBrackets() {
        assertEquals("ada@example.com", ResourceArnBuilder.extractEmail("Ada Lovelace <ada@example.com>"));
        assertEquals("ada@example.com", ResourceArnBuilder.extractEmail("ada@example.com"));
    }

    private static ContainerRequestContext mockJsonCtx(String path, String body) {
        return mockJsonCtx(path, new AtomicReference<>(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
    }

    private static ContainerRequestContext mockJsonCtx(String path, AtomicReference<InputStream> stream) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn(path);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(ctx.getEntityStream()).thenAnswer(inv -> stream.get());
        doAnswer(inv -> {
            stream.set(inv.getArgument(0));
            return null;
        }).when(ctx).setEntityStream(any(InputStream.class));
        return ctx;
    }
}
