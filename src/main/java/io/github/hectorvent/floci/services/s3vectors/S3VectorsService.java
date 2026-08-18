package io.github.hectorvent.floci.services.s3vectors;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3vectors.model.VectorBucket;
import io.github.hectorvent.floci.services.s3vectors.model.VectorIndex;
import io.github.hectorvent.floci.services.s3vectors.model.VectorData;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class S3VectorsService {
    private static final Logger LOG = Logger.getLogger(S3VectorsService.class);

    private final StorageBackend<String, VectorBucket> store;
    private final RegionResolver regionResolver;

    @Inject
    public S3VectorsService(StorageFactory factory, RegionResolver regionResolver) {
        this.store = factory.create("s3vectors", "s3vectors.json",
                new TypeReference<Map<String, VectorBucket>>() {});
        this.regionResolver = regionResolver;
    }

    private String buildStorageKey(String region, String bucketName) {
        return region + "::" + bucketName;
    }

    private String buildBucketArn(String region, String bucketName) {
        return AwsArnUtils.Arn.of("s3vectors", region, regionResolver.getAccountId(), "bucket/" + bucketName).toString();
    }

    private String buildIndexArn(String region, String bucketName, String indexName) {
        return buildBucketArn(region, bucketName) + "/index/" + indexName;
    }

    public VectorBucket createVectorBucket(String bucketName, Object encryptionConfiguration,
                                           Map<String, String> tags, String region) {
        String key = buildStorageKey(region, bucketName);
        if (store.get(key).isPresent()) {
            throw new AwsException("ConflictException", "The vector bucket " + bucketName + " already exists.", 409);
        }
        String arn = buildBucketArn(region, bucketName);
        VectorBucket bucket = new VectorBucket(bucketName, arn, encryptionConfiguration);
        if (tags != null) {
            bucket.getTags().putAll(tags);
        }
        store.put(key, bucket);
        LOG.infov("Created Vector Bucket: {0} in region {1}", bucketName, region);
        return bucket;
    }

    public VectorBucket getVectorBucket(String bucketName, String region) {
        return getVectorBucket(bucketName, null, region);
    }

    public VectorBucket getVectorBucket(String bucketName, String vectorBucketArn, String region) {
        String name = resolveBucketName(bucketName, vectorBucketArn);
        String key = buildStorageKey(region, name);
        return store.get(key).orElseThrow(() ->
                new AwsException("NotFoundException", "The vector bucket " + name + " does not exist.", 404));
    }

    public List<VectorBucket> listVectorBuckets(String region) {
        return store.scan(k -> k.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(VectorBucket::getVectorBucketName))
                .collect(Collectors.toList());
    }

    public void deleteVectorBucket(String bucketName, String region) {
        deleteVectorBucket(bucketName, null, region);
    }

    public void deleteVectorBucket(String bucketName, String vectorBucketArn, String region) {
        VectorBucket bucket = getVectorBucket(bucketName, vectorBucketArn, region);
        if (!bucket.getIndexes().isEmpty()) {
            throw new AwsException("ConflictException", "The vector bucket " + bucket.getVectorBucketName() + " is not empty.", 409);
        }
        store.delete(buildStorageKey(region, bucket.getVectorBucketName()));
        LOG.infov("Deleted Vector Bucket: {0}", bucket.getVectorBucketName());
    }

    public void putVectorBucketPolicy(String bucketName, String vectorBucketArn, String policy, String region) {
        VectorBucket bucket = getVectorBucket(bucketName, vectorBucketArn, region);
        bucket.setPolicy(policy);
        store.put(buildStorageKey(region, bucket.getVectorBucketName()), bucket);
    }

    public String getVectorBucketPolicy(String bucketName, String vectorBucketArn, String region) {
        VectorBucket bucket = getVectorBucket(bucketName, vectorBucketArn, region);
        if (bucket.getPolicy() == null) {
            throw new AwsException("NotFoundException",
                    "The vector bucket " + bucket.getVectorBucketName() + " does not have a policy.", 404);
        }
        return bucket.getPolicy();
    }

    public void deleteVectorBucketPolicy(String bucketName, String vectorBucketArn, String region) {
        VectorBucket bucket = getVectorBucket(bucketName, vectorBucketArn, region);
        if (bucket.getPolicy() == null) {
            throw new AwsException("NotFoundException",
                    "The vector bucket " + bucket.getVectorBucketName() + " does not have a policy.", 404);
        }
        bucket.setPolicy(null);
        store.put(buildStorageKey(region, bucket.getVectorBucketName()), bucket);
    }

    public VectorIndex createIndex(String bucketName, String vectorBucketArn, String indexName, int dimension,
                                   String dataType, String distanceMetric, Object metadataConfiguration,
                                   Map<String, String> tags, String region) {
        if (!"float32".equals(dataType)) {
            throw new AwsException("ValidationException", "Unsupported dataType: " + dataType + ". Only float32 is supported.", 400);
        }
        if (!"cosine".equals(distanceMetric) && !"euclidean".equals(distanceMetric)) {
            throw new AwsException("ValidationException", "Unsupported distanceMetric: " + distanceMetric + ". Supported metrics are cosine and euclidean.", 400);
        }

        VectorBucket bucket = getVectorBucket(bucketName, vectorBucketArn, region);
        if (bucket.getIndexes().containsKey(indexName)) {
            throw new AwsException("ConflictException", "The index " + indexName + " already exists.", 409);
        }

        String resolvedBucketName = bucket.getVectorBucketName();
        String arn = buildIndexArn(region, resolvedBucketName, indexName);
        VectorIndex index = new VectorIndex(indexName, arn, resolvedBucketName, dimension, dataType, distanceMetric);
        index.setMetadataConfiguration(metadataConfiguration);
        if (tags != null) {
            index.getTags().putAll(tags);
        }
        bucket.getIndexes().put(indexName, index);
        store.put(buildStorageKey(region, resolvedBucketName), bucket);
        LOG.infov("Created Index: {0} in bucket {1}", indexName, resolvedBucketName);
        return index;
    }

    public VectorIndex getIndex(String bucketName, String indexName, String region) {
        return getIndex(bucketName, indexName, null, region);
    }

    public VectorIndex getIndex(String bucketName, String indexName, String indexArn, String region) {
        String[] ids = resolveBucketAndIndex(bucketName, indexName, indexArn);
        VectorBucket bucket = getVectorBucket(ids[0], region);
        VectorIndex index = bucket.getIndexes().get(ids[1]);
        if (index == null) {
            throw new AwsException("NotFoundException", "The index " + ids[1] + " does not exist in bucket " + ids[0] + ".", 404);
        }
        return index;
    }

    public List<VectorIndex> listIndexes(String bucketName, String region) {
        return listIndexes(bucketName, null, region);
    }

    public List<VectorIndex> listIndexes(String bucketName, String vectorBucketArn, String region) {
        VectorBucket bucket = getVectorBucket(bucketName, vectorBucketArn, region);
        return bucket.getIndexes().values().stream()
                .sorted(Comparator.comparing(VectorIndex::getIndexName))
                .collect(Collectors.toList());
    }

    public void deleteIndex(String bucketName, String indexName, String region) {
        deleteIndex(bucketName, indexName, null, region);
    }

    public void deleteIndex(String bucketName, String indexName, String indexArn, String region) {
        String[] ids = resolveBucketAndIndex(bucketName, indexName, indexArn);
        VectorBucket bucket = getVectorBucket(ids[0], region);
        if (!bucket.getIndexes().containsKey(ids[1])) {
            throw new AwsException("NotFoundException", "The index " + ids[1] + " does not exist.", 404);
        }
        bucket.getIndexes().remove(ids[1]);
        store.put(buildStorageKey(region, ids[0]), bucket);
        LOG.infov("Deleted Index: {0}", ids[1]);
    }

    public void putVectors(String bucketName, String indexName, String indexArn, List<VectorData> vectors, String region) {
        String[] ids = resolveBucketAndIndex(bucketName, indexName, indexArn);
        VectorBucket bucket = getVectorBucket(ids[0], region);
        VectorIndex index = getIndex(ids[0], ids[1], region);

        for (VectorData v : vectors) {
            if (v.getData() != null && v.getData().size() != index.getDimension()) {
                throw new AwsException("ValidationException",
                        "Vector dimension " + v.getData().size() + " does not match index dimension " + index.getDimension(), 400);
            }
            index.getVectors().put(v.getKey(), v);
        }
        store.put(buildStorageKey(region, ids[0]), bucket);
        LOG.infov("Put {0} vectors into index {1}", vectors.size(), ids[1]);
    }

    public List<VectorData> getVectors(String bucketName, String indexName, String indexArn, List<String> keys, String region) {
        VectorIndex index = getIndex(bucketName, indexName, indexArn, region);
        List<VectorData> result = new ArrayList<>();
        for (String key : keys) {
            VectorData v = index.getVectors().get(key);
            if (v != null) {
                result.add(v);
            }
        }
        return result;
    }

    public void deleteVectors(String bucketName, String indexName, String indexArn, List<String> keys, String region) {
        String[] ids = resolveBucketAndIndex(bucketName, indexName, indexArn);
        VectorBucket bucket = getVectorBucket(ids[0], region);
        VectorIndex index = getIndex(ids[0], ids[1], region);
        for (String key : keys) {
            index.getVectors().remove(key);
        }
        store.put(buildStorageKey(region, ids[0]), bucket);
        LOG.infov("Deleted {0} vectors from index {1}", keys.size(), ids[1]);
    }

    public List<VectorData> listVectors(String bucketName, String indexName, String indexArn, String region) {
        VectorIndex index = getIndex(bucketName, indexName, indexArn, region);
        return index.getVectors().values().stream()
                .sorted(Comparator.comparing(VectorData::getKey))
                .collect(Collectors.toList());
    }

    public List<QueryResult> queryVectors(String bucketName, String indexName, String indexArn,
                                          List<Float> queryVector, int topK, String region) {
        VectorIndex index = getIndex(bucketName, indexName, indexArn, region);
        if (queryVector.size() != index.getDimension()) {
            throw new AwsException("ValidationException",
                    "Query vector dimension " + queryVector.size() + " does not match index dimension " + index.getDimension(), 400);
        }

        String metric = index.getDistanceMetric() != null ? index.getDistanceMetric().toLowerCase() : "cosine";
        List<QueryResult> results = new ArrayList<>();

        for (VectorData v : index.getVectors().values()) {
            double distance = 0.0;
            switch (metric) {
                case "euclidean":
                    distance = calculateEuclideanDistance(queryVector, v.getData());
                    break;
                case "cosine":
                default:
                    distance = calculateCosineSimilarity(queryVector, v.getData());
                    break;
            }
            results.add(new QueryResult(v, distance));
        }

        // Sorting:
        // For Euclidean distance, smaller distance is closer (ascending)
        // For Cosine similarity, larger score is closer (descending)
        if ("euclidean".equals(metric)) {
            results.sort(Comparator.comparingDouble(QueryResult::getDistance));
        } else {
            results.sort((r1, r2) -> Double.compare(r2.getDistance(), r1.getDistance()));
        }

        return results.stream().limit(topK).collect(Collectors.toList());
    }

    private double calculateCosineSimilarity(List<Float> v1, List<Float> v2) {
        if (v1 == null || v2 == null || v1.size() != v2.size()) return 0.0;
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            float a = v1.get(i);
            float b = v2.get(i);
            dotProduct += a * b;
            normA += a * a;
            normB += b * b;
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private double calculateEuclideanDistance(List<Float> v1, List<Float> v2) {
        if (v1 == null || v2 == null || v1.size() != v2.size()) return Double.MAX_VALUE;
        double sum = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            double diff = v1.get(i) - v2.get(i);
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    public Map<String, String> listTags(String resourceArn, String region) {
        TaggedResource resource = resolveTaggedResource(resourceArn, region);
        return new HashMap<>(resource.tags());
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        TaggedResource resource = resolveTaggedResource(resourceArn, region);
        if (tags != null) {
            resource.tags().putAll(tags);
        }
        persistTaggedResource(resource, region);
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        TaggedResource resource = resolveTaggedResource(resourceArn, region);
        if (tagKeys != null) {
            tagKeys.forEach(resource.tags()::remove);
        }
        persistTaggedResource(resource, region);
    }

    private String resolveBucketName(String bucketName, String vectorBucketArn) {
        if (bucketName != null && !bucketName.isBlank()) {
            return bucketName;
        }
        if (vectorBucketArn != null && !vectorBucketArn.isBlank()) {
            String[] parts = resourceParts(vectorBucketArn);
            if (parts.length >= 2 && "bucket".equals(parts[0])) {
                return parts[1];
            }
            throw new AwsException("ValidationException", "Invalid vector bucket ARN.", 400);
        }
        throw new AwsException("ValidationException", "vectorBucketName or vectorBucketArn is required.", 400);
    }

    private String[] resolveBucketAndIndex(String bucketName, String indexName, String indexArn) {
        if (indexArn != null && !indexArn.isBlank()) {
            String[] parts = resourceParts(indexArn);
            if (parts.length >= 4 && "bucket".equals(parts[0]) && "index".equals(parts[2])) {
                return new String[]{parts[1], parts[3]};
            }
            throw new AwsException("ValidationException", "Invalid index ARN.", 400);
        }
        if (bucketName == null || bucketName.isBlank() || indexName == null || indexName.isBlank()) {
            throw new AwsException("ValidationException",
                    "indexName and vectorBucketName, or indexArn, are required.", 400);
        }
        return new String[]{bucketName, indexName};
    }

    private String[] resourceParts(String arn) {
        try {
            return AwsArnUtils.parse(arn).resource().split("/");
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid resource ARN: " + arn, 400);
        }
    }

    private TaggedResource resolveTaggedResource(String resourceArn, String region) {
        String[] parts = resourceParts(resourceArn);
        if (parts.length >= 4 && "bucket".equals(parts[0]) && "index".equals(parts[2])) {
            VectorIndex index = getIndex(parts[1], parts[3], region);
            return new TaggedResource(parts[1], index.getTags());
        }
        if (parts.length >= 2 && "bucket".equals(parts[0])) {
            VectorBucket bucket = getVectorBucket(parts[1], region);
            return new TaggedResource(parts[1], bucket.getTags());
        }
        throw new AwsException("ValidationException", "Invalid resource ARN: " + resourceArn, 400);
    }

    private void persistTaggedResource(TaggedResource resource, String region) {
        VectorBucket bucket = getVectorBucket(resource.bucketName(), region);
        store.put(buildStorageKey(region, resource.bucketName()), bucket);
    }

    private record TaggedResource(String bucketName, Map<String, String> tags) {}

    private double calculateDotProduct(List<Float> v1, List<Float> v2) {
        if (v1 == null || v2 == null || v1.size() != v2.size()) return 0.0;
        double dotProduct = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            dotProduct += v1.get(i) * v2.get(i);
        }
        return dotProduct;
    }

    public static class QueryResult {
        private final VectorData vector;
        private final double distance;

        public QueryResult(VectorData vector, double distance) {
            this.vector = vector;
            this.distance = distance;
        }

        public VectorData getVector() { return vector; }
        public double getDistance() { return distance; }
    }
}
