package com.awesome.backend.common.storage;

import java.net.URI;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** 이미지 보관소 선택 — {@code STORAGE_BUCKET} 이 있으면 S3, 없으면 로컬 디렉토리 (D-25). */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    private final StorageProperties properties;

    public StorageConfig(StorageProperties properties) {
        this.properties = properties;
    }

    @Bean
    public ImageStore imageStore() {
        if (properties.useLocal()) {
            Path base = Path.of(properties.localBasePath());
            log.info("STORAGE_BUCKET 미설정 — 이미지를 로컬 {} 에 두고 기동한다.", base.toAbsolutePath());
            return new LocalImageStore(base, properties.localUrlPrefix());
        }

        log.info("이미지 보관소: s3://{} ({}, 임시 주소 유효시간 {})",
                properties.bucket(), properties.region(), properties.presignTtl());
        Region region = Region.of(properties.region());
        var clientBuilder = S3Client.builder().region(region);
        var presignerBuilder = S3Presigner.builder().region(region);

        // 로컬 측정: MinIO 를 S3 자리에 놓고 같은 코드 경로를 그대로 탄다. MinIO 는 가상 호스트
        // 주소(bucket.host)를 풀 수 없으니 path-style 로 부르고, 인스턴스 프로파일이 없으니
        // 자격증명을 설정에서 받는다.
        if (properties.hasEndpointOverride()) {
            log.info("이미지 보관소 엔드포인트 오버라이드: {} (path-style)", properties.endpointOverride());
            URI endpoint = URI.create(properties.endpointOverride());
            var credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
            clientBuilder.endpointOverride(endpoint)
                    .forcePathStyle(true)
                    .credentialsProvider(credentials);
            presignerBuilder.endpointOverride(endpoint)
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .credentialsProvider(credentials);
        }

        return new S3ImageStore(clientBuilder.build(), presignerBuilder.build(),
                properties.bucket(), properties.presignTtl());
    }

    /**
     * 로컬 모드에서만 파일을 직접 서빙한다. S3 모드의 조회 주소는 S3 를 직접 가리키므로
     * 이 서버를 거치지 않는다.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!properties.useLocal()) {
            return;
        }
        String location = Path.of(properties.localBasePath()).toAbsolutePath().normalize()
                .toUri().toString();
        registry.addResourceHandler(properties.localUrlPrefix() + "**")
                .addResourceLocations(location.endsWith("/") ? location : location + "/");
    }
}
