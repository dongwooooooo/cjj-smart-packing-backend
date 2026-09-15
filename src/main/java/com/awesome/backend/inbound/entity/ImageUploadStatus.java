package com.awesome.backend.inbound.entity;

/**
 * 촬영 사진이 보관소에 올라갔는지 (measurement_image.upload_status).
 *
 * <p>업로드는 세션 커밋 뒤에 비동기로 일어난다. 커밋 시점에는 키만 정해져 있고 객체는 아직 없어서,
 * 어느 상태인지 행마다 남겨야 1-6 조회가 없는 객체의 임시 주소를 발급하지 않는다.
 */
public enum ImageUploadStatus {

    /** 키만 정해졌고 보관소에는 아직 없다. */
    PENDING,

    /** 보관소에 올라갔다. 조회 주소를 발급해도 된다. */
    STORED,

    /** 재시도까지 실패했다. 사람이 봐야 하는 상태다. */
    FAILED
}
