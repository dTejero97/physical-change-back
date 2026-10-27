package com.physicalchange.repository;

import com.physicalchange.domain.PlatformPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface PlatformPaymentRepository extends JpaRepository<PlatformPayment, Long> {
    List<PlatformPayment> findByStatusAndPaymentDateBetween(String status, LocalDate from, LocalDate to);
    List<PlatformPayment> findByPaymentDateBetweenOrderByPaymentDateDescIdDesc(LocalDate from, LocalDate to);
    List<PlatformPayment> findAllByOrderByPaymentDateDesc();
    boolean existsByServiceId(Long serviceId);
}
