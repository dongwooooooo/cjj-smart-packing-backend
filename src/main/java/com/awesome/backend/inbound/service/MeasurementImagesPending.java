package com.awesome.backend.inbound.service;

import java.util.List;

/**
 * 세션은 커밋됐고 사진은 아직 보관소에 올라가지 않았다는 사건.
 *
 * <p>{@link MeasurementWriter} 가 트랜잭션 안에서 발행하고, {@link MeasurementImageUploader} 가
 * 커밋 뒤에 받아 업로드한다. 사진 바이트를 그대로 싣는 이유는 커밋 시점에 이미 메모리에 있고
 * (추론 입력이었다) 보관소에는 아직 없어서 다시 읽어 올 곳이 없기 때문이다.
 *
 * @param sessionId 사진이 귀속될 세션
 * @param images    카메라 번호·보관소 키·JPEG 바이트
 */
public record MeasurementImagesPending(Long sessionId, List<PendingImage> images) {

    /** @param key 커밋된 measurement_image.file_path 와 같은 값이다 */
    public record PendingImage(short cameraNo, String key, byte[] jpeg) {
    }
}
