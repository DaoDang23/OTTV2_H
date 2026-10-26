package ottv2.api.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

@Configuration
class ObjectStoreConfiguration {
    @Bean
    MinioClient minioClient(@Value("${ottv2.storage.endpoint}") String endpoint,
                            @Value("${ottv2.storage.access-key}") String accessKey,
                            @Value("${ottv2.storage.secret-key}") String secretKey) {
        MinioClient client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        client.setTimeout(10_000, 30_000, 30_000);
        return client;
    }

    @Bean
    ApplicationRunner ensureSourceBucket(MinioClient client, @Value("${ottv2.storage.bucket}") String bucket) {
        return args -> {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        };
    }
}

@Service
public class SourceObjectStore {
    private final MinioClient client;
    private final String bucket;

    public SourceObjectStore(MinioClient client, @Value("${ottv2.storage.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    public void put(String key, byte[] bytes) {
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .contentType("text/plain; charset=utf-8")
                    .stream(new ByteArrayInputStream(bytes), (long) bytes.length, -1L).build());
        } catch (Exception e) {
            throw new ObjectStoreException("Could not store submitted source", e);
        }
    }

    public byte[] get(String key) {
        return get(key, Integer.MAX_VALUE);
    }

    public byte[] get(String key, int maxBytes) {
        try (var stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] bytes = maxBytes == Integer.MAX_VALUE ? stream.readAllBytes() : stream.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new ObjectStoreException("Stored object exceeds the download limit", null);
            return bytes;
        } catch (IOException | io.minio.errors.MinioException e) {
            throw new ObjectStoreException("Could not read stored source", e);
        }
    }
}
