package com.awesome.backend.common.storage;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3 보관소 (D-25). 버킷은 공개 접근을 막아 두고, 화면에는 유효시간이 붙은 임시 주소만 내려보낸다.
 *
 * <p>EC2 인스턴스 프로파일로 인증하므로 키를 설정에 두지 않는다 — Lambda 호출과 같은 방식이다 (D-24).
 */
public class S3ImageStore implements ImageStore {

    private static final Logger log = LoggerFactory.getLogger(S3ImageStore.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration presignTtl;

    public S3ImageStore(S3Client s3, S3Presigner presigner, String bucket, Duration presignTtl) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.presignTtl = presignTtl;
    }

    @Override
    public Optional<byte[]> read(String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray());
        } catch (NoSuchKeyException e) {
            log.info("S3 에 없는 이미지다. key={}", key);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("S3 이미지 읽기 실패. key={}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void write(String key, byte[] jpeg) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                        .contentType("image/jpeg").build(),
                RequestBody.fromBytes(jpeg));
    }

    @Override
    public String url(String key) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(presignTtl)
                        .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                        .build())
                .url().toString();
    }
}
