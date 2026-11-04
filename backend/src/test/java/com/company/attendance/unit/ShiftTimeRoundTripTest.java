package com.company.attendance.unit;

import com.company.attendance.entity.Shift;
import com.company.attendance.repository.ShiftRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalTime;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shift-time round trip.
 *
 * <p>A real defect this catches: with `hibernate.jdbc.time_zone=UTC` and a JVM
 * default zone of anything but UTC, a shift stored as 09:30 was read back
 * shifted by the server's offset (15:00 on an IST machine). Every late,
 * early-exit and full-day calculation would then have been wrong everywhere.
 *
 * <p>The JVM zone assertion is the real guard — the round trip itself passes on
 * H2 either way, so only pinning UTC prevents the production behaviour from
 * diverging from what these tests exercise.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShiftTimeRoundTripTest {

    @Autowired
    private ShiftRepository shiftRepository;

    @Test
    @DisplayName("the test JVM runs in UTC, matching what the application pins at startup")
    void jvmRunsInUtc() {
        assertThat(TimeZone.getDefault().getID())
                .as("surefire sets -Duser.timezone=UTC; main() calls TimeZone.setDefault(UTC)")
                .isEqualTo("UTC");
    }

    @Test
    @DisplayName("a shift's start and end times survive a save and reload unchanged")
    void shiftTimesRoundTrip() {
        Shift shift = new Shift();
        shift.setCode("RT-TEST");
        shift.setName("Round Trip");
        shift.setStartTime(LocalTime.of(9, 30));
        shift.setEndTime(LocalTime.of(18, 30));

        Long id = shiftRepository.saveAndFlush(shift).getId();
        shiftRepository.flush();

        Shift reloaded = shiftRepository.findById(id).orElseThrow();
        assertThat(reloaded.getStartTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(reloaded.getEndTime()).isEqualTo(LocalTime.of(18, 30));
    }

    @Test
    @DisplayName("a midnight-crossing shift is detected by its own times, not by the clock")
    void detectsMidnightCrossing() {
        Shift night = new Shift();
        night.setStartTime(LocalTime.of(22, 0));
        night.setEndTime(LocalTime.of(7, 0));
        assertThat(night.spansMidnight()).isTrue();

        Shift day = new Shift();
        day.setStartTime(LocalTime.of(9, 30));
        day.setEndTime(LocalTime.of(18, 30));
        assertThat(day.spansMidnight()).isFalse();
    }
}
