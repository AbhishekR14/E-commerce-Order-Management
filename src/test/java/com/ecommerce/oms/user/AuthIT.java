package com.ecommerce.oms.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.support.TestDataFactory;
import com.ecommerce.oms.user.dto.LoginRequest;
import com.ecommerce.oms.user.dto.LoginResponse;
import com.ecommerce.oms.user.dto.RegisterRequest;
import com.ecommerce.oms.user.entity.User;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class AuthIT extends AbstractIntegrationTest {

    @Autowired
    UserRepository userRepository;

    @Test
    @DisplayName("register -> login -> /users/me")
    void registerLoginMe() throws Exception {
        mvc.perform(postJson("/api/v1/auth/register",
                        new RegisterRequest("Alice@Example.com", "Secret123", "Alice"), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.fullName").value("Alice"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.warehouseId").doesNotExist())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        MvcResult login = mvc.perform(postJson("/api/v1/auth/login",
                        new LoginRequest("ALICE@example.com", "Secret123"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"))
                .andReturn();
        String token = readBody(login, LoginResponse.class).accessToken();
        assertThat(token).isNotBlank();

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.fullName").value("Alice"));

        User stored = userRepository.findByEmail("alice@example.com").orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2a$").isNotEqualTo("Secret123");
    }

    @Test
    void register_duplicateEmail_409() throws Exception {
        data.customer(1);

        mvc.perform(postJson("/api/v1/auth/register",
                        new RegisterRequest("Customer1@oms.test", "Secret123", "Dup"), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    @DisplayName("weak password (no digit / too short) and bad email -> 400 VALIDATION_FAILED")
    void register_weakPassword_400() throws Exception {
        mvc.perform(postJson("/api/v1/auth/register",
                        new RegisterRequest("bob@oms.test", "lettersonly", "Bob"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));

        mvc.perform(postJson("/api/v1/auth/register",
                        new RegisterRequest("bob@oms.test", "ab1", "Bob"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("password"))));

        mvc.perform(postJson("/api/v1/auth/register",
                        Map.of("email", "not-an-email", "password", "Secret123", "fullName", ""), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[1].field").value("fullName"));
    }

    @Test
    void login_wrongPassword_401_invalidCredentials() throws Exception {
        data.customer(1);

        mvc.perform(postJson("/api/v1/auth/login", new LoginRequest("customer1@oms.test", "wrong"), null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void login_unknownEmail_401_sameCodeAsWrongPassword() throws Exception {
        mvc.perform(postJson("/api/v1/auth/login", new LoginRequest("ghost@oms.test", TestDataFactory.PASSWORD), null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void login_inactiveUser_403() throws Exception {
        data.deactivate(data.customer(1));

        mvc.perform(postJson("/api/v1/auth/login",
                        new LoginRequest("customer1@oms.test", TestDataFactory.PASSWORD), null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void me_worksForEveryRole() throws Exception {
        User admin = data.admin();
        User staff = data.staff(5L);

        mvc.perform(getJson("/api/v1/users/me", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(getJson("/api/v1/users/me", staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("WAREHOUSE_STAFF"))
                .andExpect(jsonPath("$.warehouseId").value(5));
    }
}
