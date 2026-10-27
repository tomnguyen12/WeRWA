package vn.werwa.issuance.document;

import java.io.InputStream;
import java.net.URL;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** Ghi và cấp URL tải nội dung tài liệu trên kho S3. Không xóa, không ghi đè (US-2.04-AC1). */
public class DocumentStore {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties props;

    public DocumentStore(StorageProperties props) {
        this.props = props;
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        var config = S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build();
        var s3 = S3Client.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(config)
                .httpClient(UrlConnectionHttpClient.create());
        var presigner = S3Presigner.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(config);
        if (props.endpoint() != null) {
            s3.endpointOverride(props.endpoint());
            presigner.endpointOverride(props.endpoint());
        }
        this.s3 = s3.build();
        this.presigner = presigner.build();
    }

    /** Bucket do infra tạo; thiếu thì không khởi động (cùng nguyên tắc với stream NATS, Q12). */
    public void verifyBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
        } catch (NoSuchBucketException e) {
            throw new IllegalStateException("không có bucket " + props.bucket() + " trên kho tài liệu; infra tạo bucket", e);
        }
    }

    public void put(String key, InputStream content, long size, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(props.bucket()).key(key).contentType(contentType).build(),
                RequestBody.fromInputStream(content, size));
    }

    public URL downloadUrl(String key) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(props.downloadTtl())
                        .getObjectRequest(GetObjectRequest.builder().bucket(props.bucket()).key(key).build())
                        .build())
                .url();
    }
}
