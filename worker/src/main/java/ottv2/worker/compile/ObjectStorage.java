package ottv2.worker.compile;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;

public final class ObjectStorage {
    private final MinioClient client;
    private final String bucket;

    public ObjectStorage(String endpoint, String accessKey, String secretKey, String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.client.setTimeout(10_000, 30_000, 30_000);
        this.bucket = bucket;
    }

    public byte[] get(String key, int maxBytes) throws Exception {
        try (var stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] result = stream.readNBytes(maxBytes + 1);
            if (result.length > maxBytes) throw new IllegalArgumentException("Object exceeds configured size limit: " + key);
            return result;
        }
    }

    public void put(String key, byte[] value, String contentType) throws Exception {
        client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(contentType)
                .stream(new ByteArrayInputStream(value), (long) value.length, -1L).build());
    }
}
