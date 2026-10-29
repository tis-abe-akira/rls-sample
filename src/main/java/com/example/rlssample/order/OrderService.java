package com.example.rlssample.order;

import com.example.rlssample.common.ForbiddenException;
import com.example.rlssample.tenant.CurrentUser;
import com.example.rlssample.tenant.Role;
import com.example.rlssample.tenant.TenantContext;
import com.example.rlssample.tenant.TenantId;
import com.example.rlssample.tenant.UserContext;
import com.example.rlssample.user.User;
import com.example.rlssample.user.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * RLS を効かせるには <b>@Transactional の中で SQL を実行すること</b> が前提
 * （テナントとユーザーの設定はトランザクション開始時に入るため）。
 * そのためクラスに付けて付け忘れを防いでいる。
 * <p>
 * 一般ユーザーは自分の受注だけ、管理職は自分の受注と見てよい部署の受注、管理者はテナント内の全員の受注を扱える。
 * この判定はアプリ（このクラス）と DB（RLS ポリシー owner_or_scope_or_admin）の両方で行う（多層防御）。
 */
@Service
@Transactional
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderDetailMapper orderDetailMapper;
    private final UserMapper userMapper;

    public OrderService(OrderMapper orderMapper, OrderDetailMapper orderDetailMapper, UserMapper userMapper) {
        this.orderMapper = orderMapper;
        this.orderDetailMapper = orderDetailMapper;
        this.userMapper = userMapper;
    }

    @Transactional(readOnly = true)
    public List<Order> listOrders() {
        TenantId tenantId = TenantContext.require();
        CurrentUser user = UserContext.require();
        return user.isAdmin()
                ? orderMapper.findAllByTenant(tenantId.value())
                : orderMapper.findAllByTenantAndScope(tenantId.value(), user.id(), user.scope().organizationIds());
    }

    /** WHERE句を書き忘れた「設計がイマイチな機能」の再現 */
    @Transactional(readOnly = true)
    public List<Order> listOrdersLeaky() {
        return orderMapper.findAllWithoutTenantFilter();
    }

    /** id だけで取得（IDOR パターン）。RLS により他社・他人の受注は見つからない */
    @Transactional(readOnly = true)
    public OrderWithDetails getOrder(long id) {
        return loadOrder(id);
    }

    /**
     * 受注を明細ごと作成する。受注の部署（org_id）には、名義人の今の所属部署を記録する。
     *
     * @param userId 受注の名義人。null なら自分。
     *               他人を指定できるのは、管理者（テナント内の誰でも）と管理職（見てよい部署のメンバー）だけ
     */
    public OrderWithDetails createOrder(Long userId, String title, List<NewDetail> details) {
        TenantId tenantId = TenantContext.require();
        CurrentUser me = UserContext.require();
        long ownerId = userId == null ? me.id() : userId;
        if (ownerId != me.id() && me.role() == Role.GENERAL) {
            throw new ForbiddenException("general users can only create their own orders");
        }
        // 範囲の外のユーザーは RLS により見つからない
        User owner = userMapper.findById(ownerId)
                .orElseThrow(() -> new ForbiddenException("cannot create orders for user " + ownerId));
        if (ownerId != me.id() && !me.isAdmin() && !me.scope().organizationIds().contains(owner.orgId())) {
            throw new ForbiddenException("cannot create orders for user " + ownerId);
        }
        long id = orderMapper.insert(tenantId.value(), owner.id(), owner.orgId(), title);
        for (NewDetail detail : details) {
            orderDetailMapper.insert(tenantId.value(), id, detail.item(), detail.quantity(), detail.unitPrice());
        }
        return loadOrder(id);
    }

    /** id だけで更新（IDOR パターン）。RLS により他社・他人の受注は 0 件更新になる */
    public OrderWithDetails updateTitle(long id, String title) {
        if (orderMapper.updateTitle(id, title) == 0) {
            throw new OrderNotFoundException(id);
        }
        return loadOrder(id);
    }

    /** 明細を追加する。親の受注が見えなければ（他社・他人の受注なら）404 */
    public OrderWithDetails addDetail(long orderId, NewDetail detail) {
        Order order = orderMapper.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        orderDetailMapper.insert(order.tenantId(), orderId, detail.item(), detail.quantity(), detail.unitPrice());
        return loadOrder(orderId);
    }

    /** id だけで削除（IDOR パターン）。RLS により他社・他人の受注は 0 件削除になる。明細は CASCADE で消える */
    public void deleteOrder(long id) {
        if (orderMapper.deleteById(id) == 0) {
            throw new OrderNotFoundException(id);
        }
    }

    private OrderWithDetails loadOrder(long id) {
        Order order = orderMapper.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        return OrderWithDetails.of(order, orderDetailMapper.findByOrderId(order.tenantId(), id));
    }

    /** 作成・追加する明細。tenant_id と order_id はサーバー側で決める */
    public record NewDetail(String item, int quantity, int unitPrice) {
    }
}
