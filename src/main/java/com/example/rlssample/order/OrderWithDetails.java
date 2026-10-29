package com.example.rlssample.order;

import java.util.List;

/** 受注1件と、その明細 */
public record OrderWithDetails(Long id, Long tenantId, Long userId, String title, List<OrderDetail> details) {

    static OrderWithDetails of(Order order, List<OrderDetail> details) {
        return new OrderWithDetails(order.id(), order.tenantId(), order.userId(), order.title(), details);
    }
}
