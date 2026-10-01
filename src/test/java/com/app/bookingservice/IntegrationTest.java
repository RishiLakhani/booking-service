package com.app.bookingservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** Base for tests that need the full app (with MockMvc) against a throwaway Postgres. */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "JWT_SECRET=test-secret-test-secret-test-secret-123",
        "ADMIN_SECRET=test-admin-secret"})
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {
}
