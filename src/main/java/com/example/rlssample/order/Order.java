package com.example.rlssample.order;

public record Order(Long id, Long tenantId, Long userId, String title) {
}
