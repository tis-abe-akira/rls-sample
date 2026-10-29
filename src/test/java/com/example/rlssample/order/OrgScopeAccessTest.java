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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static com.example.rlssample.support.Actor.ALICE;
import static com.example.rlssample.support.Actor.A_SHOJI;
import static com.example.rlssample.support.Actor.BOB;
import static com.example.rlssample.support.Actor.CAROL;
import static com.example.rlssample.support.Actor.KEN;
import static com.example.rlssample.support.Actor.MIKA;
import static com.example.rlssample.support.Actor.SALES_DEPT_1;
import static com.example.rlssample.support.Actor.SALES_DEPT_2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 組織階層による見える範囲を確認する。判断はアプリ（AccessScopeResolver）、強制は DB（RLS）。
 * <pre>
 *   bob   一般（第一営業部）            → 自分の受注だけ           = 受注 1
 *   ken   部長（第一営業部）            → 第一営業部の受注         = 受注 1, 6
 *   mika  事業部長（営業事業部）        → 事業部と配下の部の受注   = 受注 1, 2, 6
 *   alice 管理者（管理本部）            → テナント内の全受注       = 受注 1, 2, 5, 6
 * </pre>
 * 事前に docker compose up -d で DB を起動しておくこと。
 */
@SpringBootTest
class OrgScopeAccessTest {

    @Autowired
    OrderService orderService;

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    UserService userService;

    @Autowired
    UserMapper userMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    // ---- 見える範囲 --------------------------------------------------------

    @Test
    @DisplayName("一般・部長・事業部長・管理者で、見える受注が階層どおりに広がる")
    void visibleOrdersFollowHierarchy() {
        assertThat(ids(BOB.run(orderService::listOrders))).containsExactly(1L);
        assertThat(ids(KEN.run(orderService::listOrders))).containsExactly(1L, 6L);
        assertThat(ids(MIKA.run(orderService::listOrders))).containsExactly(1L, 2L, 6L);
        assertThat(ids(ALICE.run(orderService::listOrders))).containsExactly(1L, 2L, 5L, 6L);
    }

    @Test
    @DisplayName("WHERE句を書き忘れたSQLでも、部長・事業部長に見えるのは範囲の中だけ（DB が強制する）")
    void leakyQueryIsLimitedToScope() {
        assertThat(ids(KEN.run(orderService::listOrdersLeaky))).containsExactly(1L, 6L);
        assertThat(ids(MIKA.run(orderService::listOrdersLeaky))).containsExactly(1L, 2L, 6L);
    }

    @Test
    @DisplayName("IDOR: 部長は範囲の外（第二営業部）の受注を取得・更新できない。事業部長はできる")
    void managerCannotAccessOutsideScope() {
        long carolsOrderId = 2L;

        assertThatThrownBy(() -> KEN.run(() -> orderService.getOrder(carolsOrderId)))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> KEN.run(() -> orderService.updateTitle(carolsOrderId, "改ざん")))
                .isInstanceOf(OrderNotFoundException.class);
        assertThat(MIKA.run(() -> orderService.getOrder(carolsOrderId)).orgId()).isEqualTo(SALES_DEPT_2);
    }

    @Test
    @DisplayName("事業部長でも、配下でない部署（管理本部）の受注は見えない")
    void divisionManagerCannotSeeOtherDivision() {
        assertThatThrownBy(() -> MIKA.run(() -> orderService.getOrder(5L)))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // ---- 範囲の中での操作 ----------------------------------------------------

    @Test
    @DisplayName("部長は、部員の受注を更新し、明細を追加し、削除できる")
    void managerCanManageMembersOrder() {
        long id = BOB.run(() -> orderService.createOrder(null, "ボブの営業資料印刷", List.of())).id();

        KEN.run(() -> {
            orderService.addDetail(id, new OrderService.NewDetail("カラー印刷", 100, 50));
            assertThat(orderService.updateTitle(id, "ボブの営業資料印刷（部長承認）").details()).hasSize(1);
        });
        assertThat(BOB.run(() -> orderService.getOrder(id)).title()).isEqualTo("ボブの営業資料印刷（部長承認）");

        KEN.run(() -> orderService.deleteOrder(id));
        assertThatThrownBy(() -> BOB.run(() -> orderService.getOrder(id)))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("部長は部員の名義で受注を作れる（受注の部署は名義人の部署）。範囲の外のメンバーの名義では作れない")
    void managerCanCreateOrderOnlyForMembersInScope() {
        OrderWithDetails created = KEN.run(() -> orderService.createOrder(BOB.userId(), "ボブの代理発注", List.of()));
        assertThat(created.userId()).isEqualTo(BOB.userId());
        assertThat(created.orgId()).isEqualTo(SALES_DEPT_1);
        KEN.run(() -> orderService.deleteOrder(created.id()));

        assertThatThrownBy(() -> KEN.run(() -> orderService.createOrder(CAROL.userId(), "範囲外の代理発注", List.of())))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("事業部長は、配下の部（第二営業部）のメンバーの名義でも受注を作れる")
    void divisionManagerCanCreateOrderForSubDepartmentMember() {
        OrderWithDetails created = MIKA.run(() -> orderService.createOrder(CAROL.userId(), "キャロルの代理発注", List.of()));

        assertThat(created.orgId()).isEqualTo(SALES_DEPT_2);
        MIKA.run(() -> orderService.deleteOrder(created.id()));
    }

    @Test
    @DisplayName("アプリのチェックをすり抜けても、範囲の外の部署の受注は DB（WITH CHECK）が拒否する")
    void outsideScopeInsertIsRejectedByDatabase() {
        assertThatThrownBy(() -> KEN.run(() ->
                tx.execute(status -> orderMapper.insert(A_SHOJI.value(), CAROL.userId(), SALES_DEPT_2, "範囲外"))))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security policy");
    }

    // ---- ユーザー ----------------------------------------------------------

    @Test
    @DisplayName("ユーザー一覧: 部長は部員、事業部長は事業部のメンバー（管理本部のアリスは出ない）")
    void managersListMembersInScope() {
        assertThat(userIds(KEN.run(userService::listUsers))).containsExactly(2L, 4L, 8L);
        assertThat(userIds(MIKA.run(userService::listUsers))).containsExactly(2L, 3L, 4L, 8L, 9L);
    }

    @Test
    @DisplayName("アプリのチェックをすり抜けて全員分の SQL を流しても、部長に見えるのは部員だけ（DB が絞る）")
    void leakyUserQueryIsLimitedToScope() {
        List<User> visible = KEN.run(() -> tx.execute(status -> userMapper.findAllByTenant(A_SHOJI.value())));

        assertThat(userIds(visible)).containsExactly(2L, 4L, 8L);
    }

    // ---- 異動 --------------------------------------------------------------

    @Test
    @DisplayName("異動: ボブが第二営業部に移っても、過去の受注は第一営業部のデータのまま（元の部長が見られる）")
    void ordersStayWithOriginalDepartmentAfterTransfer() {
        transferBob(SALES_DEPT_2);
        try {
            assertThat(ids(KEN.run(orderService::listOrders))).contains(1L);
            assertThat(ids(BOB.run(orderService::listOrders))).contains(1L);   // 自分の受注は名義で見える
            assertThat(userIds(KEN.run(userService::listUsers))).doesNotContain(BOB.userId());
        } finally {
            transferBob(SALES_DEPT_1);
        }
    }

    private void transferBob(long orgId) {
        // 管理者として更新する（users の更新ポリシーは管理者だけ）。JdbcTemplate も同じトランザクション・接続に参加する
        ALICE.run(() -> tx.executeWithoutResult(status ->
                jdbc.update("UPDATE users SET org_id = ? WHERE id = ?", orgId, BOB.userId())));
    }

    private static List<Long> ids(List<Order> orders) {
        return orders.stream().map(Order::id).toList();
    }

    private static List<Long> userIds(List<User> users) {
        return users.stream().map(User::id).toList();
    }
}
