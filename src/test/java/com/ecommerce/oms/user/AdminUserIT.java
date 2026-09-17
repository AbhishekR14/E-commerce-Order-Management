package com.ecommerce.oms.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.support.TestDataFactory;
import com.ecommerce.oms.user.dto.CreateUserRequest;
import com.ecommerce.oms.user.dto.LoginRequest;
import com.ecommerce.oms.user.dto.UpdateUserRequest;
import com.ecommerce.oms.user.dto.UserResponse;
import com.ecommerce.oms.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

class AdminUserIT extends AbstractIntegrationTest {

    @Autowired
    UserRepository userRepository;

    @Test
    void createStaff_201() throws Exception {
        User admin = data.admin();

        MvcResult result = mvc.perform(postJson("/api/v1/admin/users",
                        new CreateUserRequest("Staff.BLR@oms.test", "Staff123", "BLR Staff", Role.WAREHOUSE_STAFF, 1L),
                        admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("staff.blr@oms.test"))
                .andExpect(jsonPath("$.role").value("WAREHOUSE_STAFF"))
                .andExpect(jsonPath("$.warehouseId").value(1))
                .andReturn();

        // the new staff member can log in
        UserResponse created = readBody(result, UserResponse.class);
        mvc.perform(postJson("/api/v1/auth/login", new LoginRequest(created.email(), "Staff123"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.warehouseId").value(1));
    }

    @Test
    void createAdmin_201_withoutWarehouse() throws Exception {
        User admin = data.admin();

        mvc.perform(postJson("/api/v1/admin/users",
                        new CreateUserRequest("admin2@oms.test", "Admin123", "Second Admin", Role.ADMIN, null), admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.warehouseId").doesNotExist());
    }

    @Test
    @DisplayName("bad role/warehouse combinations -> 422 USER_ROLE_INVALID")
    void create_roleWarehouseRules_422() throws Exception {
        User admin = data.admin();

        mvc.perform(postJson("/api/v1/admin/users",
                        new CreateUserRequest("s@oms.test", "Staff123", "S", Role.WAREHOUSE_STAFF, null), admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("USER_ROLE_INVALID"));

        mvc.perform(postJson("/api/v1/admin/users",
                        new CreateUserRequest("a@oms.test", "Admin123", "A", Role.ADMIN, 1L), admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("USER_ROLE_INVALID"));

        mvc.perform(postJson("/api/v1/admin/users",
                        new CreateUserRequest("c@oms.test", "Cust1234", "C", Role.CUSTOMER, null), admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("USER_ROLE_INVALID"));

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void create_duplicateEmail_409() throws Exception {
        User admin = data.admin();

        mvc.perform(postJson("/api/v1/admin/users",
                        new CreateUserRequest("ADMIN@oms.test", "Admin123", "Dup", Role.ADMIN, null), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void create_invalidBody_400() throws Exception {
        User admin = data.admin();

        mvc.perform(postJson("/api/v1/admin/users", "{\"email\":\"x@oms.test\",\"password\":\"short\"}", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").isArray());
    }

    @Test
    void list_pagedAndFilteredByRole() throws Exception {
        User admin = data.admin();
        data.customer(1);
        data.customer(2);
        data.staff(1L);

        mvc.perform(getJson("/api/v1/admin/users", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(4)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(1));

        mvc.perform(getJson("/api/v1/admin/users?role=CUSTOMER&size=1&sort=email,desc", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].email").value("customer2@oms.test"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(getJson("/api/v1/admin/users?role=NOPE", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void patch_deactivateAndReassign() throws Exception {
        User admin = data.admin();
        User staff = data.staff(1L);
        User customer = data.customer(1);

        mvc.perform(patchJson("/api/v1/admin/users/" + staff.getId(), new UpdateUserRequest(null, 2L), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warehouseId").value(2))
                .andExpect(jsonPath("$.active").value(true));

        mvc.perform(patchJson("/api/v1/admin/users/" + customer.getId(), new UpdateUserRequest(false, null), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // the deactivated customer can no longer log in
        mvc.perform(postJson("/api/v1/auth/login",
                        new LoginRequest(customer.getEmail(), TestDataFactory.PASSWORD), null))
                .andExpect(status().isForbidden());

        // a warehouse cannot be assigned to a non-staff user
        mvc.perform(patchJson("/api/v1/admin/users/" + customer.getId(), new UpdateUserRequest(null, 1L), admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("USER_ROLE_INVALID"));

        mvc.perform(patchJson("/api/v1/admin/users/999999", new UpdateUserRequest(true, null), admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void adminEndpoints_403_forCustomerAndStaff() throws Exception {
        User customer = data.customer(1);
        User staff = data.staff(1L);

        mvc.perform(getJson("/api/v1/admin/users", customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(postJson("/api/v1/admin/users",
                        new CreateUserRequest("x@oms.test", "Admin123", "X", Role.ADMIN, null), staff))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
