package com.example.rlssample.tenant;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 現在のスレッドで処理中のユーザーを保持する。{@link TenantContext} と対になる。
 * <p>
 * 値をセットしてよいのは {@link TenantFilter}（リクエストの入口）と、
 * バッチやテストで明示的に {@link #runAs} を使う場合だけ。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static Optional<CurrentUser> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** ユーザー必須の処理で使う。未設定・未解決なら即座に失敗させる（fail-closed）。 */
    public static CurrentUser require() {
        return current().filter(CurrentUser::isResolved).orElseThrow(
                () -> new IllegalStateException("User is not resolved for the current thread"));
    }

    /**
     * 指定ユーザーとして処理を実行し、終了後に必ず元へ戻す。
     * <p>
     * {@link TenantContext#runAs} と同じ理由で、トランザクションの途中で別のユーザーに切り替えることは禁止する。
     */
    public static <T> T runAs(CurrentUser user, Supplier<T> action) {
        CurrentUser previous = CURRENT.get();
        if (TransactionSynchronizationManager.isActualTransactionActive() && !user.equals(previous)) {
            throw new IllegalStateException(
                    "Cannot switch user inside a transaction: current=" + previous + ", requested=" + user);
        }
        CURRENT.set(user);
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

    public static void runAs(CurrentUser user, Runnable action) {
        runAs(user, () -> {
            action.run();
            return null;
        });
    }
}
