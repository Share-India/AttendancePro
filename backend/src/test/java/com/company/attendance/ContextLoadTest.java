package com.company.attendance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the whole wiring stands up: Hibernate maps every entity, Spring
 * Security builds its chain, and the JWT key passes its length check.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContextLoadTest {

    @Test
    void contextLoads() {
        // Failure here means a mapping or bean-wiring problem, not a logic bug.
    }
}
