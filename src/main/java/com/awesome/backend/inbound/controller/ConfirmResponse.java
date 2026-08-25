package com.awesome.backend.inbound.controller;

import com.awesome.backend.inbound.entity.Product;

/** 측정 확정 응답 (02 §1-4). */
public record ConfirmResponse(Long productId, String dimStatus, String dimMethod) {

    public static ConfirmResponse from(Product product) {
        return new ConfirmResponse(product.id(), product.dimStatus(), product.dimMethod());
    }
}
