package com.example.rlssample.order;

/** orgId は受注を作った時点の名義人の部署 */
public record Order(Long id, Long tenantId, Long userId, Long orgId, String title) {
}
