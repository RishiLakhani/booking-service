package com.app.bookingservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "JWT_SECRET=test-secret-test-secret-test-secret-123",
        "ADMIN_SECRET=test-admin-secret"})
class BookingServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
