package com.example.rlssample.tenant;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 現在のスレッドで処理中のテナントを保持する。
 * <p>
 * 値をセットしてよいのは {@link TenantFilter}（リクエストの入口）と、
 * バッチやテストで明示的に {@link #runAs} を使う場合だけ。
 * サービス層やMapperでは「読む」だけにする。
 */
public final class TenantContext {

    private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<TenantId> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** テナント必須の処理で使う。未設定なら即座に失敗させる（fail-closed）。 */
    public static TenantId require() {
        return current().orElseThrow(
                () -> new IllegalStateException("Tenant is not set for the current thread"));
    }

    /**
     * 指定テナントとして処理を実行し、終了後に必ず元へ戻す。
     * <p>
     * トランザクションの途中で別のテナントに切り替えることは禁止する。DB へのテナント設定
     * （{@link TenantAwareTransactionManager}）は BEGIN のときに1回だけ行われるので、途中で切り替えると
     * Java 側は新しいテナント、DB 側は古いテナント（または未設定）のまま動き、両者が食い違うため。
     * 別のテナントとして処理したい場合は、トランザクションの外で runAs を呼ぶこと。
     */
    public static <T> T runAs(TenantId tenantId, Supplier<T> action) {
        TenantId previous = CURRENT.get();
        if (TransactionSynchronizationManager.isActualTransactionActive() && !tenantId.equals(previous)) {
            throw new IllegalStateException(
                    "Cannot switch tenant inside a transaction: current=" + previous + ", requested=" + tenantId);
        }
        CURRENT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(TenantId tenantId, Runnable action) {
        runAs(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
