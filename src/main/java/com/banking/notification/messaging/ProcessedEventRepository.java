package com.banking.notification.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    @Modifying
    @Query(value = "insert into processed_events (id, processed_at) values (:id, now()) "
            + "on conflict (id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("id") String id);

    /**
     * Removes the claim again, so a failed action can be retried.
     * Named differently from JpaRepository.deleteById on purpose, so the
     * inherited delete is not shadowed by this one.
     */
    @Modifying
    @Query(value = "delete from processed_events where id = :id", nativeQuery = true)
    int deleteClaim(@Param("id") String id);

    @Modifying
    @Query("delete from ProcessedEvent p where p.processedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
