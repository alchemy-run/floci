package io.floci.aws.services.textract;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Reads the document a Textract request names and returns the keys its mock
 * response may be filed under, most specific first: {@code "<Bucket>/<Name>"}
 * for an S3 document, then {@code "sha256:<hex>"} of the document's bytes,
 * whether they came inline ({@code Bytes}) or from S3. The content key lets a
 * mock config answer for a document whatever bucket it was uploaded to.
 * <p>
 * An S3 document Textract can't read is refused with
 * {@code InvalidS3ObjectException}, as Textract refuses it.
 */
@ApplicationScoped
public class TextractDocuments {
    private static final String UNREADABLE =
            "Unable to get object metadata from S3. Check object key, region and/or access permissions.";

    private final S3Service s3Service;

    @Inject
    public TextractDocuments(S3Service s3Service) {
        this.s3Service = s3Service;
    }

    /** Keys for {@code Document} (sync) or {@code DocumentLocation} (async) shapes; empty when absent. */
    public List<String> keys(JsonNode document) {
        List<String> keys = new ArrayList<>();
        if (document == null || !document.isObject()) {
            return keys;
        }
        JsonNode s3Object = document.get("S3Object");
        if (document.hasNonNull("Bytes")) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(document.get("Bytes").asText());
            } catch (IllegalArgumentException e) {
                throw new AwsException("InvalidParameterException", "Document.Bytes is not valid base64.", 400);
            }
            keys.add(contentKey(bytes));
        } else if (s3Object != null && s3Object.isObject()) {
            String bucket = s3Object.path("Bucket").asText(null);
            String name = s3Object.path("Name").asText(null);
            if (bucket == null || name == null) {
                throw new AwsException("InvalidS3ObjectException", UNREADABLE, 400);
            }
            byte[] bytes;
            try {
                bytes = s3Service.getObject(bucket, name).getData();
            } catch (AwsException e) {
                throw new AwsException("InvalidS3ObjectException", UNREADABLE, 400);
            }
            keys.add(bucket + "/" + name);
            keys.add(contentKey(bytes));
        }
        return keys;
    }

    static String contentKey(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
