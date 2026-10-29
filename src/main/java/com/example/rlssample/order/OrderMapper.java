package com.example.rlssample.order;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 受注 Mapper。SQL本体は resources/mapper/OrderMapper.xml。
 * <p>
 * 「現場によくある書き方」をあえて混ぜている:
 * <ul>
 *   <li>{@link #findAllByTenant} / {@link #findAllByTenantAndScope} … 正しく tenant_id（と自分・見てよい部署）で絞っている</li>
 *   <li>{@link #findAllWithoutTenantFilter} … WHERE句を書き忘れた（設計がイマイチな機能）</li>
 *   <li>{@link #findById} / {@link #updateTitle} / {@link #deleteById} … id だけで扱う典型的な IDOR パターン</li>
 * </ul>
 * RLS があれば、後ろ4つも他社のデータも、見てはいけない人のデータも返さない。
 */
@Mapper
public interface OrderMapper {

    /** 管理者向け: テナント内の全員の受注 */
    List<Order> findAllByTenant(@Param("tenantId") long tenantId);

    /** 一般ユーザー・管理職向け: 自分の受注と、見てよい部署の受注（一般ユーザーは orgIds が空） */
    List<Order> findAllByTenantAndScope(@Param("tenantId") long tenantId,
                                        @Param("userId") long userId,
                                        @Param("orgIds") Collection<Long> orgIds);

    List<Order> findAllWithoutTenantFilter();

    Optional<Order> findById(@Param("id") long id);

    long insert(@Param("tenantId") long tenantId,
                @Param("userId") long userId,
                @Param("orgId") long orgId,
                @Param("title") String title);

    int updateTitle(@Param("id") long id, @Param("title") String title);

    int deleteById(@Param("id") long id);
}
