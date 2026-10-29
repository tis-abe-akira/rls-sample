package com.example.rlssample.org;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 組織 Mapper。SQL本体は resources/mapper/OrganizationMapper.xml。
 * <p>
 * 組織図はテナント内の全員が見られる（RLS でテナントの外は見えない）。
 */
@Mapper
public interface OrganizationMapper {

    /** 指定した部署と、その配下のすべての部署の ID */
    List<Long> findSelfAndDescendantIds(@Param("orgId") long orgId);
}
