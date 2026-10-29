package com.example.rlssample.tenant;

/** テナントの中でのロール。users.role の値と一致させる。 */
public enum Role {
    /** 一般ユーザー: 自分のデータだけ読み書きできる */
    GENERAL,
    /** 管理職（部長・事業部長）: 自分のデータに加えて、見てよい部署（{@link AccessScope}）のデータを読み書きできる */
    MANAGER,
    /** 管理者: テナント内の全員のデータを読み書きできる */
    ADMIN
}
