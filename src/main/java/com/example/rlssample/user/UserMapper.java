package com.example.rlssample.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ユーザー Mapper。SQL本体は resources/mapper/UserMapper.xml。
 * <p>
 * RLS により、一般ユーザーには自分の行だけ、管理職には見てよい部署のメンバー、管理者にはテナント内の全員が見える。
 */
@Mapper
public interface UserMapper {

    Optional<User> findBySubject(@Param("subject") String subject);

    Optional<User> findById(@Param("id") long id);

    List<User> findAllByTenant(@Param("tenantId") long tenantId);

    /** 管理職向け: 指定した部署に所属するメンバー */
    List<User> findAllByOrganizations(@Param("tenantId") long tenantId, @Param("orgIds") Collection<Long> orgIds);
}
