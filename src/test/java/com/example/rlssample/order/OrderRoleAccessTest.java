package com.example.rlssample.order;

import com.example.rlssample.common.ForbiddenException;
import com.example.rlssample.user.User;
import com.example.rlssample.user.UserMapper;
import com.example.rlssample.user.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static com.example.rlssample.support.Actor.ALICE;
import static com.example.rlssample.support.Actor.A_SHOJI;
import static com.example.rlssample.support.Actor.BOB;
import static com.example.rlssample.support.Actor.CAROL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * テナントの中の権限（PERMISSIVE ポリシー）を確認する。
 * 一般ユーザー（bob / carol）は自分のデータだけ、管理者（alice）はテナント内の全員のデータを扱える。
 * 事前に docker compose up -d で DB を起動しておくこと。
 */
@SpringBootTest
class OrderRoleAccessTest {

    @Autowired
    OrderService orderService;

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    OrderDetailMapper orderDetailMapper;

    @Autowired
    UserService userService;

    @Autowired
    UserMapper userMapper;

    @Autowired
    TransactionTemplate tx;

    // ---- 一般ユーザー ----------------------------------------------------

    @Test
    @DisplayName("一般ユーザーの一覧は、自分の受注だけ")
    void general_listOrders_returnsOnlyOwn() {
        List<Order> orders = BOB.run(orderService::listOrders);

        assertThat(orders).isNotEmpty()
                .allSatisfy(o -> assertThat(o.userId()).isEqualTo(BOB.userId()));
    }

    @Test
    @DisplayName("WHERE句を書き忘れたSQLでも、一般ユーザーに見えるのは自分の受注だけ")
    void general_leakyQuery_returnsOnlyOwn() {
        List<Order> orders = BOB.run(orderService::listOrdersLeaky);

        assertThat(orders).isNotEmpty()
                .allSatisfy(o -> assertThat(o.userId()).isEqualTo(BOB.userId()));
    }

    @Test
    @DisplayName("明細を WHERE なしで直接 SELECT しても、一般ユーザーに見えるのは自分の受注の明細だけ")
    void general_leakyDetailQuery_returnsOnlyOwn() {
        List<Long> ownOrderIds = BOB.run(orderService::listOrders).stream().map(Order::id).toList();
        List<OrderDetail> details = BOB.run(() -> tx.execute(status -> orderDetailMapper.findAllWithoutTenantFilter()));

        assertThat(details).isNotEmpty()
                .allSatisfy(d -> assertThat(ownOrderIds).contains(d.orderId()));
    }

    @Test
    @DisplayName("IDOR: 一般ユーザーは、同じテナントでも他人の受注を取得・更新・削除できない")
    void general_cannotAccessOthersOrder() {
        long carolsOrderId = CAROL.run(orderService::listOrders).getFirst().id();
        String originalTitle = CAROL.run(() -> orderService.getOrder(carolsOrderId)).title();

        assertThatThrownBy(() -> BOB.run(() -> orderService.getOrder(carolsOrderId)))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> BOB.run(() -> orderService.updateTitle(carolsOrderId, "改ざん")))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> BOB.run(() -> orderService.deleteOrder(carolsOrderId)))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> BOB.run(() ->
                orderService.addDetail(carolsOrderId, new OrderService.NewDetail("勝手に追加", 1, 1))))
                .isInstanceOf(OrderNotFoundException.class);

        assertThat(CAROL.run(() -> orderService.getOrder(carolsOrderId)).title()).isEqualTo(originalTitle);
    }

    @Test
    @DisplayName("一般ユーザーが他人名義で受注を作ろうとすると、アプリが 403 で止める")
    void general_cannotCreateOrderForOthers_app() {
        assertThatThrownBy(() -> BOB.run(() -> orderService.createOrder(CAROL.userId(), "なりすまし", List.of())))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("アプリのチェックをすり抜けても、他人名義の受注は DB（WITH CHECK）が拒否する")
    void general_cannotCreateOrderForOthers_db() {
        assertThatThrownBy(() -> BOB.run(() ->
                tx.execute(status -> orderMapper.insert(A_SHOJI.value(), CAROL.userId(), 3L, "なりすまし"))))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security policy");
    }

    @Test
    @DisplayName("アプリのチェックをすり抜けても、他人の受注への明細追加は DB（WITH CHECK）が拒否する")
    void general_cannotAddDetailToOthersOrder_db() {
        long carolsOrderId = CAROL.run(orderService::listOrders).getFirst().id();

        assertThatThrownBy(() -> BOB.run(() ->
                tx.execute(status -> orderDetailMapper.insert(A_SHOJI.value(), carolsOrderId, "勝手に追加", 1, 1))))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security policy");
    }

    @Test
    @DisplayName("一般ユーザーは、自分の受注を明細ごと作成・更新・削除できる")
    void general_canManageOwnOrder() {
        BOB.run(() -> {
            OrderWithDetails created = orderService.createOrder(null, "ボブの消耗品",
                    List.of(new OrderService.NewDetail("ボールペン", 10, 100)));
            assertThat(created.userId()).isEqualTo(BOB.userId());

            OrderWithDetails withDetail = orderService.addDetail(created.id(), new OrderService.NewDetail("ノート", 5, 200));
            assertThat(withDetail.details()).hasSize(2);

            assertThat(orderService.updateTitle(created.id(), "ボブの消耗品（追加あり）").title())
                    .isEqualTo("ボブの消耗品（追加あり）");

            orderService.deleteOrder(created.id());
            assertThatThrownBy(() -> orderService.getOrder(created.id()))
                    .isInstanceOf(OrderNotFoundException.class);
        });
    }

    @Test
    @DisplayName("一般ユーザーにはユーザー一覧を見せない（アプリが 403）")
    void general_cannotListUsers() {
        assertThatThrownBy(() -> BOB.run(userService::listUsers))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("アプリのチェックをすり抜けて全員分の SQL を流しても、一般ユーザーに見えるのは自分の行だけ（DB が絞る）")
    void general_seesOnlySelfInUsersTable() {
        List<User> visible = BOB.run(() -> tx.execute(status -> userMapper.findAllByTenant(A_SHOJI.value())));

        assertThat(visible).singleElement().satisfies(u -> assertThat(u.id()).isEqualTo(BOB.userId()));
    }

    // ---- 管理者 ----------------------------------------------------------

    @Test
    @DisplayName("管理者は、テナント内の他人の受注を取得・更新・削除できる")
    void admin_canManageOthersOrder() {
        long id = BOB.run(() -> orderService.createOrder(null, "ボブの出張手配",
                List.of(new OrderService.NewDetail("新幹線", 2, 14000)))).id();

        ALICE.run(() -> {
            OrderWithDetails order = orderService.getOrder(id);
            assertThat(order.userId()).isEqualTo(BOB.userId());
            assertThat(order.details()).hasSize(1);

            orderService.addDetail(id, new OrderService.NewDetail("ホテル", 1, 12000));
            assertThat(orderService.updateTitle(id, "ボブの出張手配（承認済み）").details()).hasSize(2);
        });

        assertThat(BOB.run(() -> orderService.getOrder(id)).title()).isEqualTo("ボブの出張手配（承認済み）");

        ALICE.run(() -> orderService.deleteOrder(id));
        assertThatThrownBy(() -> BOB.run(() -> orderService.getOrder(id)))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("管理者は、テナント内の他人名義で受注を作成できる")
    void admin_canCreateOrderForOthers() {
        OrderWithDetails created = ALICE.run(() -> orderService.createOrder(CAROL.userId(), "キャロルの代理発注", List.of()));

        assertThat(created.userId()).isEqualTo(CAROL.userId());
        assertThat(CAROL.run(() -> orderService.getOrder(created.id())).title()).isEqualTo("キャロルの代理発注");

        ALICE.run(() -> orderService.deleteOrder(created.id()));
    }

    @Test
    @DisplayName("管理者のユーザー一覧は、自社の全員6人（他社のユーザーは出ない）")
    void admin_listsAllUsersInTenant() {
        List<User> users = ALICE.run(userService::listUsers);

        assertThat(users).hasSize(6)
                .allSatisfy(u -> assertThat(u.tenantId()).isEqualTo(A_SHOJI.value()));
    }
}
