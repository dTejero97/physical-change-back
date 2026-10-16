package com.physicalchange.repository;

import com.physicalchange.domain.Payment;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDate;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Page<Payment> findByTrainerIdOrderByDueDateDesc(Long trainerId, Pageable p);
    Page<Payment> findByClientIdOrderByDueDateDesc(Long clientId, Pageable p);

    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.trainerId = :t and p.status = :s")
    BigDecimal sumByTrainerAndStatus(@Param("t") Long trainerId, @Param("s") String status);

    @Query("select count(p) from Payment p where p.trainerId = :t and p.status = 'PENDING' and p.dueDate < :today")
    long countOverdue(@Param("t") Long trainerId, @Param("today") LocalDate today);
}
