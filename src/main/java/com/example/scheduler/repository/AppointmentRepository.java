package com.example.scheduler.repository;

import com.example.scheduler.domain.Appointment;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {
    // Friendly early check only; the exclusion constraint is the actual concurrency guarantee.
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM appointments a
                WHERE a.provider_id = :providerId
                  AND a.status = 'ACTIVE'
                  AND tstzrange(a.start_time, a.end_time, '[)') &&
                      tstzrange(:startTime, :endTime, '[)')
            )
            """, nativeQuery = true)
    boolean hasActiveOverlap(@Param("providerId") UUID providerId,
                             @Param("startTime") Instant startTime,
                             @Param("endTime") Instant endTime);
}
