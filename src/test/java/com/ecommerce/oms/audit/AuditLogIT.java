package com.ecommerce.oms.audit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.audit.entity.AuditLog;
import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.inventory.dto.AdjustmentRequest;
import com.ecommerce.oms.order.dto.OrderResponse;
import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.support.TestDataFactory;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuditLogIT extends AbstractIntegrationTest {

    @Autowired
    AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("every committed event lands in audit_logs with action, entity, actor and JSON details; admins can query it")
    void eventsAreAudited() throws Exception {
        User admin = data.admin();
        User alice = data.customer(1);
        Warehouse blr = data.warehouse("BLR-1", 1);
        Category c = data.category("Electronics", "18");
        Product phone = data.product("PH-001", "100.00", c);

        // an admin adjustment -> InventoryAdjusted with the admin as actor
        mvc.perform(postJson("/api/v1/admin/inventory/adjustments",
                        new AdjustmentRequest(phone.getId(), blr.getId(), 10, "stock in"), admin))
                .andExpect(status().isCreated());
        await().atMost(5, SECONDS).untilAsserted(() ->
                assertThat(auditLogRepository.findAllByEntityTypeAndEntityIdOrderByIdAsc("INVENTORY", phone.getId()))
                        .singleElement().satisfies(a -> {
                            assertThat(a.getAction()).isEqualTo("InventoryAdjusted");
                            assertThat(a.getActorId()).isEqualTo(admin.getId());
                            assertThat(a.getDetails()).contains("\"delta\":10");
                        }));

        // a checkout -> OrderPlaced (actor = customer) then OrderStatusChanged PLACED->CONFIRMED (actor = system)
        data.cartWith(alice, phone, 1);
        long orderId = readBody(mvc.perform(postJson("/api/v1/checkout", TestDataFactory.checkoutRequest(null), alice)
                        .header("Idempotency-Key", "audit-0001-order"))
                .andExpect(status().isCreated()).andReturn(), OrderResponse.class).id();
        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<AuditLog> rows = auditLogRepository.findAllByEntityTypeAndEntityIdOrderByIdAsc("ORDER", orderId);
            assertThat(rows).extracting(AuditLog::getAction).containsExactly("OrderPlaced", "OrderStatusChanged");
            assertThat(rows.get(0).getActorId()).isEqualTo(alice.getId());
            assertThat(rows.get(1).getActorId()).isNull();
            assertThat(rows.get(1).getDetails()).contains("\"from\":\"PLACED\"").contains("\"to\":\"CONFIRMED\"");
        });

        // the admin query: by entity, by actor, paged newest first; forbidden for others
        mvc.perform(getJson("/api/v1/admin/audit-logs?entityType=ORDER&entityId=" + orderId, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].action").value("OrderStatusChanged"))
                .andExpect(jsonPath("$.content[1].action").value("OrderPlaced"))
                .andExpect(jsonPath("$.content[1].actorId").value(alice.getId()))
                .andExpect(jsonPath("$.content[1].details").isString())
                .andExpect(jsonPath("$.content[1].createdAt").isString());
        mvc.perform(getJson("/api/v1/admin/audit-logs?actorId=" + admin.getId(), admin))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].entityType").value("INVENTORY"));
        mvc.perform(getJson("/api/v1/admin/audit-logs", admin))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(getJson("/api/v1/admin/audit-logs", alice))
                .andExpect(status().isForbidden());
    }
}
