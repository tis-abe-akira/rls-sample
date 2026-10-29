package com.example.rlssample.order;

import com.example.rlssample.order.OrderService.NewDetail;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<Order> list() {
        return orderService.listOrders();
    }

    @GetMapping("/leaky")
    public List<Order> listLeaky() {
        return orderService.listOrdersLeaky();
    }

    @GetMapping("/{id}")
    public OrderWithDetails get(@PathVariable long id) {
        return orderService.getOrder(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderWithDetails create(@RequestBody CreateOrderRequest request) {
        List<NewDetail> details = request.details() == null ? List.of() : request.details();
        return orderService.createOrder(request.userId(), request.title(), details);
    }

    @PutMapping("/{id}")
    public OrderWithDetails update(@PathVariable long id, @RequestBody UpdateOrderRequest request) {
        return orderService.updateTitle(id, request.title());
    }

    @PostMapping("/{id}/details")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderWithDetails addDetail(@PathVariable long id, @RequestBody NewDetail request) {
        return orderService.addDetail(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        orderService.deleteOrder(id);
    }

    /**
     * リクエストに tenantId は含めない。テナントは必ずサーバー側（TenantContext）で決める。
     * userId は省略すると自分。他人を指定できるのは管理者と、管理職（見てよい部署のメンバーだけ）。
     */
    public record CreateOrderRequest(Long userId, String title, List<NewDetail> details) {
    }

    public record UpdateOrderRequest(String title) {
    }
}
