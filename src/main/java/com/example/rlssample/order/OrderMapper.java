package com.example.rlssample.order;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 受注 Mapper。SQL本体は resources/mapper/OrderMapper.xml。
 * <p>
 * 「現場によくある書き方」をあえて混ぜている:
 * <ul>
 *   <li>{@link #findAllByTenant} / {@link #findAllByTenantAndUser} … 正しく tenant_id（と user_id）で絞っている</li>
 *   <li>{@link #findAllWithoutTenantFilter} … WHERE句を書き忘れた（設計がイマイチな機能）</li>
 *   <li>{@link #findById} / {@link #updateTitle} / {@link #deleteById} … id だけで扱う典型的な IDOR パターン</li>
 * </ul>
 * RLS があれば、後ろ4つも他社のデータも、（一般ユーザーなら）他人のデータも返さない。
 */
@Mapper
public interface OrderMapper {

    /** 管理者向け: テナント内の全員の受注 */
    List<Order> findAllByTenant(@Param("tenantId") long tenantId);

    /** 一般ユーザー向け: 自分の受注だけ */
    List<Order> findAllByTenantAndUser(@Param("tenantId") long tenantId, @Param("userId") long userId);

    List<Order> findAllWithoutTenantFilter();

    Optional<Order> findById(@Param("id") long id);

    long insert(@Param("tenantId") long tenantId,
                @Param("userId") long userId,
                @Param("title") String title);

    int updateTitle(@Param("id") long id, @Param("title") String title);

    int deleteById(@Param("id") long id);
}
