package com.example.rlssample.order;

public record OrderDetail(Long id, Long tenantId, Long orderId, String item, Integer quantity, Integer unitPrice) {
}
