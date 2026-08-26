package com.awesome.backend.inbound.service;

/**
 * 촬영함 카메라 1대가 찍은 사진 1장. 추론 입력이자 1-3 응답 {@code images} 의 원본이다.
 *
 * <p>모델은 사진을 카메라별 슬롯에 넣어야 하므로 번호가 함께 다닌다 — 순서가 어긋나면
 * 오차가 커진다 (ai/inference/dimension.py). 카메라 n 은 모델 view {@code "1-n"} 에 대응한다.
 *
 * @param cameraNo 카메라 번호 1~3
 * @param jpeg     JPEG 바이트
 * @param key      보관소 키. DB 에 남는 값이며 조회 주소는 응답 때 발급한다 (D-25)
 */
public record CameraImage(short cameraNo, byte[] jpeg, String key) {

    /** 모델 {@code views} 인자 표기. 촬영 회차(shot)는 1 로 고정이다. */
    public String view() {
        return "1-" + cameraNo;
    }
}
