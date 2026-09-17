package com.ecommerce.oms.warehouse;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.dto.WarehouseRequest;
import com.ecommerce.oms.warehouse.dto.WarehouseResponse;
import com.ecommerce.oms.warehouse.dto.WarehouseStatusRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WarehouseIT extends AbstractIntegrationTest {

    @Test
    void crudAndOrdering() throws Exception {
        User admin = data.admin();

        long mum = readBody(mvc.perform(postJson("/api/v1/admin/warehouses",
                        new WarehouseRequest("MUM-1", "Mumbai Hub", "Mumbai", 2), admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("MUM-1"))
                .andExpect(jsonPath("$.priority").value(2))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn(), WarehouseResponse.class).id();
        long blr = readBody(mvc.perform(postJson("/api/v1/admin/warehouses",
                        new WarehouseRequest("BLR-1", "Bengaluru Hub", "Bengaluru", 1), admin))
                .andExpect(status().isCreated())
                .andReturn(), WarehouseResponse.class).id();

        // listed in allocation order: priority, then id
        mvc.perform(getJson("/api/v1/admin/warehouses", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(blr))
                .andExpect(jsonPath("$[1].id").value(mum));

        mvc.perform(putJson("/api/v1/admin/warehouses/" + mum,
                        new WarehouseRequest("MUM-1", "Mumbai Central", "Mumbai", 1), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mumbai Central"))
                .andExpect(jsonPath("$.priority").value(1));
        // code is immutable
        mvc.perform(putJson("/api/v1/admin/warehouses/" + mum,
                        new WarehouseRequest("MUM-2", "Mumbai Central", "Mumbai", 1), admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(patchJson("/api/v1/admin/warehouses/" + mum + "/status", new WarehouseStatusRequest(false), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(patchJson("/api/v1/admin/warehouses/999/status", new WarehouseStatusRequest(true), admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationDuplicatesAndAuth() throws Exception {
        User admin = data.admin();
        data.warehouse("BLR-1", 1);

        mvc.perform(postJson("/api/v1/admin/warehouses",
                        new WarehouseRequest("BLR-1", "Dup", "Bengaluru", 1), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        mvc.perform(postJson("/api/v1/admin/warehouses",
                        Map.of("code", "blr 2", "name", "", "city", "X", "priority", 0), admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[1].field").value("name"))
                .andExpect(jsonPath("$.errors[2].field").value("priority"));

        mvc.perform(getJson("/api/v1/admin/warehouses", data.customer(1)))
                .andExpect(status().isForbidden());
    }
}
