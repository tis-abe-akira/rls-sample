package com.example.rlssample.order;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 受注明細 Mapper。SQL本体は resources/mapper/OrderDetailMapper.xml。
 * <p>
 * 明細は「親の受注が見えるなら見える」（RLS ポリシー visible_parent_order）。
 */
@Mapper
public interface OrderDetailMapper {

    List<OrderDetail> findByOrderId(@Param("tenantId") long tenantId, @Param("orderId") long orderId);

    /** ❌ WHERE句の書き忘れ。子テーブルを直接 SELECT されるケースの再現 */
    List<OrderDetail> findAllWithoutTenantFilter();

    long insert(@Param("tenantId") long tenantId,
                @Param("orderId") long orderId,
                @Param("item") String item,
                @Param("quantity") int quantity,
                @Param("unitPrice") int unitPrice);
}
