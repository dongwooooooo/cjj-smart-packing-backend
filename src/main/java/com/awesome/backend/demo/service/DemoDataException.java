package com.awesome.backend.demo.service;

/**
 * 시연 데이터 파일이 잘못됐을 때. 메시지에 파일 이름과 어느 항목이 문제인지 담는다 —
 * 시연 직전에 파일을 고쳐야 하는 상황이라 무엇을 고칠지 바로 보여야 한다.
 */
public class DemoDataException extends RuntimeException {

    public DemoDataException(String message) {
        super(message);
    }

    public DemoDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
