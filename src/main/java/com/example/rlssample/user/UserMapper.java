package com.example.rlssample.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * ユーザー Mapper。SQL本体は resources/mapper/UserMapper.xml。
 * <p>
 * RLS により、一般ユーザーには自分の行だけ、管理者にはテナント内の全員が見える。
 */
@Mapper
public interface UserMapper {

    Optional<User> findBySubject(@Param("subject") String subject);

    List<User> findAllByTenant(@Param("tenantId") long tenantId);
}
