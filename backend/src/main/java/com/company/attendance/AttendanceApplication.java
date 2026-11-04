package com.company.attendance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.TimeZone;

@SpringBootApplication
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@EnableAsync
public class AttendanceApplication {

    public static void main(String[] args) {
        // Pin the JVM to UTC before anything reads a clock or opens a connection.
        //
        // This is not cosmetic. Hibernate is configured with
        // `hibernate.jdbc.time_zone=UTC` so that Instants round-trip unambiguously,
        // and that setting also applies to TIME columns. On a server whose default
        // zone is not UTC, a shift stored as 09:30 comes back as 15:00 on an
        // IST machine — every late, early-exit and full-day calculation would then
        // be wrong by the server's offset, silently and everywhere.
        //
        // Business dates are never taken from this default: they are resolved
        // explicitly against the employee's office timezone. Pinning UTC here
        // makes that the only way to get a local date, which is the intent.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        SpringApplication.run(AttendanceApplication.class, args);
    }
}
