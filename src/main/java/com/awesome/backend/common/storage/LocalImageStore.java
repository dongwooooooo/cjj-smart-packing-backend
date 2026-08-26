package com.awesome.backend.common.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 로컬 디렉토리 보관소 — 버킷 설정이 없을 때 쓴다 (개발·테스트).
 *
 * <p>키를 그대로 상대 경로로 쓰고, 조회 주소는 정적 서빙 경로가 된다.
 * 키가 기준 디렉토리를 벗어나면 거절한다 — 키는 바깥에서 들어온 값일 수 있다.
 */
public class LocalImageStore implements ImageStore {

    private static final Logger log = LoggerFactory.getLogger(LocalImageStore.class);

    private final Path base;
    private final String urlPrefix;

    public LocalImageStore(Path base, String urlPrefix) {
        this.base = base.toAbsolutePath().normalize();
        this.urlPrefix = urlPrefix.endsWith("/") ? urlPrefix : urlPrefix + "/";
    }

    @Override
    public Optional<byte[]> read(String key) {
        Path file = resolve(key);
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            log.warn("이미지 읽기 실패. key={}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void write(String key, byte[] jpeg) {
        Path file = resolve(key);
        if (file == null) {
            throw new IllegalArgumentException("기준 디렉토리를 벗어난 키입니다: " + key);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, jpeg);
        } catch (IOException e) {
            throw new IllegalStateException("이미지 저장 실패: " + key, e);
        }
    }

    @Override
    public String url(String key) {
        return urlPrefix + key;
    }

    /** 기준 디렉토리 밖으로 나가는 키는 null 이다. */
    private Path resolve(String key) {
        Path file = base.resolve(key).toAbsolutePath().normalize();
        return file.startsWith(base) ? file : null;
    }
}
