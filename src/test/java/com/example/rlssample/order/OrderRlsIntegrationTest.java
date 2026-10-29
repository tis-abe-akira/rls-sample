package com.example.rlssample.order;

import com.example.rlssample.support.Actor;
import com.example.rlssample.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static com.example.rlssample.support.Actor.ALICE;
import static com.example.rlssample.support.Actor.A_SHOJI;
import static com.example.rlssample.support.Actor.BOB;
import static com.example.rlssample.support.Actor.B_KOGYO;
import static com.example.rlssample.support.Actor.DAVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「2テナント越境テスト」。テナントの壁（RESTRICTIVE ポリシー）を確認する。
 * <b>管理者として動いても</b>他社のデータは見えないことを確認するため、主に管理者（alice / dave）で実行する。
 * 事前に docker compose up -d で DB を起動しておくこと。
 * <p>
 * 注意: テストメソッドに @Transactional を付けないこと。
 * テスト開始時点（TenantContext 未設定）でトランザクションが始まってしまい、
 * テナントが設定されない＝何も見えないテストになる。
 */
@SpringBootTest
class OrderRlsIntegrationTest {

    @Autowired
    OrderService orderService;

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    OrderDetailMapper orderDetailMapper;

    @Autowired
    TransactionTemplate tx;

    @Test
    @DisplayName("管理者の一覧は、自社の全員分だけ返る")
    void listOrders_returnsOnlyOwnTenant() {
        List<Order> orders = ALICE.run(orderService::listOrders);

        assertThat(orders).isNotEmpty()
                .allSatisfy(o -> assertThat(o.tenantId()).isEqualTo(A_SHOJI.value()));
        assertThat(orders).extracting(Order::userId).contains(ALICE.userId(), BOB.userId());
    }

    @Test
    @DisplayName("WHERE句を書き忘れたSQLでも、管理者に見えるのは自社分だけ")
    void leakyQuery_isStillIsolatedByRls() {
        List<Order> orders = ALICE.run(orderService::listOrdersLeaky);

        assertThat(orders).isNotEmpty()
                .allSatisfy(o -> assertThat(o.tenantId()).isEqualTo(A_SHOJI.value()));
    }

    @Test
    @DisplayName("子テーブル（明細）を WHERE なしで直接 SELECT しても、自社分だけ")
    void leakyDetailQuery_isStillIsolatedByRls() {
        List<OrderDetail> details = ALICE.run(() -> tx.execute(status -> orderDetailMapper.findAllWithoutTenantFilter()));

        assertThat(details).isNotEmpty()
                .allSatisfy(d -> assertThat(d.tenantId()).isEqualTo(A_SHOJI.value()));
    }

    @Test
    @DisplayName("IDOR: 管理者でも、他社の受注IDを指定すると見つからない（404相当）")
    void getOtherTenantsOrder_isNotFound() {
        long bOrderId = anyOrderIdOf(DAVE);

        assertThatThrownBy(() -> ALICE.run(() -> orderService.getOrder(bOrderId)))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("IDOR: 管理者でも、他社の受注は更新・削除できず、消えずに残る")
    void updateOrDeleteOtherTenantsOrder_isNotFound_andRowSurvives() {
        long bOrderId = anyOrderIdOf(DAVE);
        String originalTitle = DAVE.run(() -> orderService.getOrder(bOrderId)).title();

        assertThatThrownBy(() -> ALICE.run(() -> orderService.updateTitle(bOrderId, "改ざん")))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> ALICE.run(() -> orderService.deleteOrder(bOrderId)))
                .isInstanceOf(OrderNotFoundException.class);

        OrderWithDetails survived = DAVE.run(() -> orderService.getOrder(bOrderId));
        assertThat(survived.title()).isEqualTo(originalTitle);
        assertThat(survived.details()).isNotEmpty();
    }

    @Test
    @DisplayName("IDOR: 管理者でも、他社の受注に明細は追加できない")
    void addDetailToOtherTenantsOrder_isNotFound() {
        long bOrderId = anyOrderIdOf(DAVE);

        assertThatThrownBy(() -> ALICE.run(() ->
                orderService.addDetail(bOrderId, new OrderService.NewDetail("勝手に追加", 1, 1))))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("WITH CHECK: 他社の tenant_id で INSERT しようとするとDBが拒否する")
    void insertWithOtherTenantId_isRejectedByDatabase() {
        assertThatThrownBy(() -> ALICE.run(() ->
                tx.execute(status -> orderMapper.insert(B_KOGYO.value(), DAVE.userId(), "なりすまし注文"))))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security policy");
    }

    @Test
    @DisplayName("複合外部キー: 管理者が自社の tenant_id で、他社のユーザー名義の受注を作ろうとするとDBが拒否する")
    void insertForOtherTenantsUser_isRejectedByForeignKey() {
        assertThatThrownBy(() -> ALICE.run(() ->
                tx.execute(status -> orderMapper.insert(A_SHOJI.value(), DAVE.userId(), "他社ユーザー名義"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("foreign key");
    }

    @Test
    @DisplayName("fail-closed: テナントもユーザーも未設定なら何も見えない")
    void withoutTenant_nothingIsVisible() {
        List<Order> orders = orderService.listOrdersLeaky();

        assertThat(orders).isEmpty();
    }

    @Test
    @DisplayName("fail-closed: テナントだけ設定してユーザーが未設定なら何も見えない")
    void tenantWithoutUser_nothingIsVisible() {
        List<Order> orders = TenantContext.runAs(A_SHOJI, orderService::listOrdersLeaky);

        assertThat(orders).isEmpty();
    }

    @Test
    @DisplayName("トランザクション外で Mapper を直接呼ぶと何も見えない（@Transactional 付け忘れの検知）")
    void outsideTransaction_nothingIsVisible() {
        List<Order> orders = ALICE.run(orderMapper::findAllWithoutTenantFilter);

        assertThat(orders).isEmpty();
    }

    @Test
    @DisplayName("トランザクションの途中で別テナントに切り替えようとすると例外（DB 側は切り替わらないため）")
    void switchTenantInsideTransaction_isRejected() {
        assertThatThrownBy(() -> ALICE.run(() ->
                tx.execute(status -> DAVE.run(orderMapper::findAllWithoutTenantFilter))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot switch tenant inside a transaction");
    }

    @Test
    @DisplayName("トランザクションの途中でも、同じテナント・同じユーザーでの runAs は許される")
    void sameActorInsideTransaction_isAllowed() {
        List<Order> orders = ALICE.run(() ->
                tx.execute(status -> ALICE.run(orderMapper::findAllWithoutTenantFilter)));

        assertThat(orders).isNotEmpty()
                .allSatisfy(o -> assertThat(o.tenantId()).isEqualTo(A_SHOJI.value()));
    }

    @Test
    @DisplayName("自社の受注は明細ごと作成・取得・削除でき、削除すると明細も消える")
    void ownTenant_canCreateGetAndDelete() {
        ALICE.run(() -> {
            OrderWithDetails created = orderService.createOrder(null, "テスト用キーボード",
                    List.of(new OrderService.NewDetail("キーボード", 7, 5000)));
            assertThat(created.tenantId()).isEqualTo(A_SHOJI.value());
            assertThat(created.userId()).isEqualTo(ALICE.userId());
            assertThat(created.details()).singleElement()
                    .satisfies(d -> assertThat(d.tenantId()).isEqualTo(A_SHOJI.value()));

            orderService.deleteOrder(created.id());
            assertThatThrownBy(() -> orderService.getOrder(created.id()))
                    .isInstanceOf(OrderNotFoundException.class);
            List<OrderDetail> remaining = tx.execute(status -> orderDetailMapper.findAllWithoutTenantFilter());
            assertThat(remaining).noneMatch(d -> d.orderId().equals(created.id()));
        });
    }

    private long anyOrderIdOf(Actor admin) {
        return admin.run(orderService::listOrders).getFirst().id();
    }
}
