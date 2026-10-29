package com.example.rlssample.tenant;

/**
 * テナント（企業）ID の値オブジェクト。
 * 素の long を引き回さず型で表すことで、「どこでテナントを扱っているか」がコード上で見えるようにする。
 */
public record TenantId(long value) {

    public TenantId {
        if (value <= 0) {
            throw new IllegalArgumentException("TenantId must be positive: " + value);
        }
    }

    public static TenantId of(long value) {
        return new TenantId(value);
    }

    /** PostgreSQL の set_config に渡す文字列表現 */
    public String asSettingValue() {
        return Long.toString(value);
    }
}
