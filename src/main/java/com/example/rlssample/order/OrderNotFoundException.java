package com.example.rlssample.order;

/**
 * 他社の受注も「存在しない」として扱う。
 * 403 ではなく 404 を返すことで、他社のIDが存在するかどうかも推測させない。
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(long id) {
        super("Order not found: " + id);
    }
}
