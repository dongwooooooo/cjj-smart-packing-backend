package com.awesome.backend.common.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이미지 보관 설정 (application.yml 의 {@code storage.*}).
 *
 * @param bucket        S3 버킷. 비우면 로컬 디렉토리를 쓴다 (개발·테스트)
 * @param region        버킷 리전
 * @param presignTtl    임시 조회 주소의 유효시간. 화면을 열어 둔 채로 만료되지 않을 만큼은 길어야 하고,
 *                      주소가 새어 나갔을 때 오래 살아 있지 않을 만큼은 짧아야 한다
 * @param localBasePath 버킷이 없을 때 파일을 두는 위치
 * @param localUrlPrefix 로컬 모드에서 붙이는 조회 경로 접두사
 * @param endpointOverride S3 대신 부를 주소. 로컬 측정에서 MinIO 를 가리키는 데 쓴다.
 *                      비우면 실제 AWS 다 ({@code inference.endpoint-override} 와 같은 방식)
 * @param accessKey     엔드포인트 오버라이드에서 쓸 정적 자격증명. 실 AWS 는 EC2 인스턴스 프로파일을
 *                      쓰므로 비운다 (D-24)
 * @param secretKey     위와 같다
 */
@ConfigurationProperties(prefix = "storage")
public record StorageProperties(String bucket, String region, Duration presignTtl,
                                String localBasePath, String localUrlPrefix,
                                String endpointOverride, String accessKey, String secretKey) {

    public boolean useLocal() {
        return bucket == null || bucket.isBlank();
    }

    /** MinIO 같은 S3 호환 저장소를 가리키는지. 이때는 path-style 주소와 정적 자격증명을 쓴다. */
    public boolean hasEndpointOverride() {
        return endpointOverride != null && !endpointOverride.isBlank();
    }
}
