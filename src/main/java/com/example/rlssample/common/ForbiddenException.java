package com.example.rlssample.common;

/**
 * テナントの中で、ロール上許されない操作（一般ユーザーが他人名義の受注を作る、など）。
 * <p>
 * 他テナントや他人の「既存の行」へのアクセスは 404（存在しない扱い）にする。
 * 403 にするのは、相手の行の存在を推測させない「自分の権限の話」だけ。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
