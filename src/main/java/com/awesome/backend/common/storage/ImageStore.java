package com.awesome.backend.common.storage;

import java.util.Optional;

/**
 * 상품·촬영 이미지 보관소 (D-25).
 *
 * <p>DB 에는 URL 이 아니라 키만 남긴다 — S3 조회 주소는 유효시간이 있어 저장해 두면 곧 못 쓰는 값이 된다.
 * 응답을 만들 때마다 {@link #url(String)} 으로 새로 발급한다.
 *
 * <p>구현은 S3({@link S3ImageStore})와 로컬 디렉토리({@link LocalImageStore}) 두 가지이며
 * {@code STORAGE_BUCKET} 설정 여부로 갈린다 — 추론 클라이언트가 mock 과 갈리는 것과 같은 방식이다 (D-04).
 */
public interface ImageStore {

    /** 없는 키면 빈 Optional. 읽기 실패도 같게 취급한다 — 호출자 입장에서 "사진 없음"으로 같다. */
    Optional<byte[]> read(String key);

    void write(String key, byte[] jpeg);

    /** 화면이 바로 쓸 수 있는 조회 주소. S3 는 유효시간이 붙은 임시 주소다. */
    String url(String key);
}
