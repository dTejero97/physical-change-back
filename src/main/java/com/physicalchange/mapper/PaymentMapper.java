package com.physicalchange.mapper;

import com.physicalchange.domain.Payment;
import com.physicalchange.dto.Dtos.PaymentDto;
import java.time.LocalDate;

public final class PaymentMapper {
    private PaymentMapper() {}

    /** Un pago pendiente con fecha límite anterior a hoy está vencido. */
    public static PaymentDto toDto(Payment p, String clientName, LocalDate today) {
        boolean overdue = "PENDING".equals(p.getStatus()) && p.getDueDate().isBefore(today);
        return new PaymentDto(p.getId(), p.getTrainerId(), p.getClientId(), clientName, p.getAmount(), p.getConcept(),
                p.getDueDate(), p.getPaidDate(), p.getStatus(), overdue);
    }
}
