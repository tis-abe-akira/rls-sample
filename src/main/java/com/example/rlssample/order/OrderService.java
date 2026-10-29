package com.example.rlssample.order;

import com.example.rlssample.common.ForbiddenException;
import com.example.rlssample.tenant.CurrentUser;
import com.example.rlssample.tenant.TenantContext;
import com.example.rlssample.tenant.TenantId;
import com.example.rlssample.tenant.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * RLS を効かせるには <b>@Transactional の中で SQL を実行すること</b> が前提
 * （テナントとユーザーの設定はトランザクション開始時に入るため）。
 * そのためクラスに付けて付け忘れを防いでいる。
 * <p>
 * 一般ユーザーは自分の受注だけ、管理者はテナント内の全員の受注を扱える。
 * この判定はアプリ（このクラス）と DB（RLS ポリシー owner_or_admin）の両方で行う（多層防御）。
 */
@Service
@Transactional
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderDetailMapper orderDetailMapper;

    public OrderService(OrderMapper orderMapper, OrderDetailMapper orderDetailMapper) {
        this.orderMapper = orderMapper;
        this.orderDetailMapper = orderDetailMapper;
    }

    @Transactional(readOnly = true)
    public List<Order> listOrders() {
        TenantId tenantId = TenantContext.require();
        CurrentUser user = UserContext.require();
        return user.isAdmin()
                ? orderMapper.findAllByTenant(tenantId.value())
                : orderMapper.findAllByTenantAndUser(tenantId.value(), user.id());
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
     * 受注を明細ごと作成する。
     *
     * @param userId 受注の名義人。null なら自分。他人を指定できるのは管理者だけ
     */
    public OrderWithDetails createOrder(Long userId, String title, List<NewDetail> details) {
        TenantId tenantId = TenantContext.require();
        CurrentUser me = UserContext.require();
        long owner = userId == null ? me.id() : userId;
        if (!me.isAdmin() && owner != me.id()) {
            throw new ForbiddenException("general users can only create their own orders");
        }
        long id = orderMapper.insert(tenantId.value(), owner, title);
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
