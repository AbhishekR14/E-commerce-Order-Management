package com.ecommerce.oms.support;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import com.ecommerce.oms.common.config.AsyncConfig;
import com.ecommerce.oms.security.JwtService;
import com.ecommerce.oms.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Base class for {@code *IT} tests: full Spring context on H2 (profile {@code test}), MockMvc, a clean
 * database before every test, and helpers for JSON and bearer tokens. Never annotate subclasses with
 * {@code @Transactional}: async listeners and concurrency tests need real commits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected TestDataFactory data;

    @Autowired
    protected DatabaseCleaner cleaner;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    @Qualifier(AsyncConfig.EVENT_EXECUTOR)
    protected ThreadPoolTaskExecutor eventExecutor;

    /** Let the previous test's after-commit listeners finish before truncating, or their rows leak into this test. */
    @BeforeEach
    void cleanDatabase() {
        awaitListeners();
        cleaner.clean();
    }

    protected void awaitListeners() {
        await().atMost(10, SECONDS).until(() ->
                eventExecutor.getActiveCount() == 0 && eventExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    /** {@code Authorization} header value for the given user (token minted directly, no login round-trip). */
    protected String bearer(User user) {
        return "Bearer " + jwtService.issue(user);
    }

    protected MockHttpServletRequestBuilder getJson(String path, User as) {
        return withAuth(MockMvcRequestBuilders.get(path).accept(MediaType.APPLICATION_JSON), as);
    }

    protected MockHttpServletRequestBuilder postJson(String path, Object body, User as) throws Exception {
        return withAuth(MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(toJson(body)), as);
    }

    protected MockHttpServletRequestBuilder patchJson(String path, Object body, User as) throws Exception {
        return withAuth(MockMvcRequestBuilders.patch(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(toJson(body)), as);
    }

    protected MockHttpServletRequestBuilder putJson(String path, Object body, User as) throws Exception {
        return withAuth(MockMvcRequestBuilders.put(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(toJson(body)), as);
    }

    protected MockHttpServletRequestBuilder deleteJson(String path, User as) {
        return withAuth(MockMvcRequestBuilders.delete(path).accept(MediaType.APPLICATION_JSON), as);
    }

    protected String toJson(Object body) throws Exception {
        return body instanceof String s ? s : objectMapper.writeValueAsString(body);
    }

    protected <T> T readBody(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), type);
    }

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder, User as) {
        return as == null ? builder : builder.header(HttpHeaders.AUTHORIZATION, bearer(as));
    }
}
