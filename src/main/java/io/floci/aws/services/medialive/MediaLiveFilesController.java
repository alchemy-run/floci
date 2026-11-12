package io.floci.aws.services.medialive;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The S3 objects a running channel's encoder reads its {@code s3://} sources from, by the token the encoder was
 * started with: the token names the channel's account and Region. Ranges are served, so ffmpeg can seek in an MP4.
 */
@Path("/_floci/medialive/files")
public class MediaLiveFilesController {

    static final String PATH = "/_floci/medialive/files/";
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private final MediaLiveEncoder encoder;
    private final S3Service s3;

    @Inject
    public MediaLiveFilesController(MediaLiveEncoder encoder, S3Service s3) {
        this.encoder = encoder;
        this.s3 = s3;
    }

    @HEAD
    @Path("/{token}/{bucket}/{key: .+}")
    public Response head(@PathParam("token") String token, @PathParam("bucket") String bucket,
                         @PathParam("key") String key) {
        S3Object object = object(token, bucket, key);
        if (object == null) {
            return Response.status(404).build();
        }
        return Response.ok().header("Content-Length", object.getData().length).header("Accept-Ranges", "bytes")
                .type(type(object)).build();
    }

    @GET
    @Path("/{token}/{bucket}/{key: .+}")
    public Response get(@PathParam("token") String token, @PathParam("bucket") String bucket,
                        @PathParam("key") String key, @HeaderParam("Range") String range) {
        S3Object object = object(token, bucket, key);
        if (object == null) {
            return Response.status(404).type(MediaType.TEXT_PLAIN).entity("The object doesn't exist, or the URL "
                    + "has expired.").build();
        }
        byte[] data = object.getData();
        Matcher matcher = range == null ? null : RANGE.matcher(range.trim());
        if (matcher != null && matcher.matches() && data.length > 0) {
            long start;
            long end;
            if (matcher.group(1).isEmpty()) {
                long suffix = Long.parseLong(matcher.group(2));
                start = Math.max(0, data.length - suffix);
                end = data.length - 1;
            } else {
                start = Long.parseLong(matcher.group(1));
                end = matcher.group(2).isEmpty() ? data.length - 1 : Math.min(data.length - 1,
                        Long.parseLong(matcher.group(2)));
            }
            if (start >= data.length || start > end) {
                return Response.status(416).header("Content-Range", "bytes */" + data.length).build();
            }
            return Response.status(206).entity(Arrays.copyOfRange(data, (int) start, (int) end + 1)).type(type(object))
                    .header("Content-Range", "bytes " + start + "-" + end + "/" + data.length)
                    .header("Accept-Ranges", "bytes").build();
        }
        return Response.ok(data, type(object)).header("Accept-Ranges", "bytes").build();
    }

    private static String type(S3Object object) {
        return object.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM : object.getContentType();
    }

    private S3Object object(String token, String bucket, String key) {
        String[] issued = encoder.token(token);
        if (issued == null) {
            return null;
        }
        try {
            return RequestScopes.callAs(issued[0], issued[1], () -> s3.getObject(bucket, key));
        } catch (AwsException e) {
            return null;
        }
    }
}
