package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.mapper.PaymentMapper;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;

/** Cobros del entrenador a sus clientes. El cliente solo consulta los suyos. */
@Service
public class PaymentService {
    private final PaymentRepository payments;
    private final UserRepository users;
    private final AccessService access;

    public PaymentService(PaymentRepository p, UserRepository u, AccessService a) { payments = p; users = u; access = a; }

    private String nameOf(Long userId) {
        return users.findById(userId).map(AppUser::getFullName).orElse("—");
    }

    @Transactional(readOnly = true)
    public Page<PaymentDto> list(Pageable p) {
        AuthUser me = access.current();
        LocalDate today = LocalDate.now();
        Page<Payment> page = me.role() == Role.CLIENT
                ? payments.findByClientIdOrderByDueDateDesc(me.id(), p)
                : payments.findByTrainerIdOrderByDueDateDesc(me.id(), p);
        return page.map(x -> PaymentMapper.toDto(x, nameOf(x.getClientId()), today));
    }

    @Transactional
    public PaymentDto create(PaymentRequest r) {
        access.assertCanAccessClient(r.clientId());
        AuthUser me = access.current();
        AppUser client = users.findById(r.clientId()).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        Payment p = new Payment();
        p.setTrainerId(me.role() == Role.ADMIN ? client.getTrainerId() : me.id());
        p.setClientId(client.getId());
        p.setAmount(r.amount());
        p.setConcept(r.concept());
        p.setDueDate(r.dueDate());
        p.setStatus("PENDING");
        return PaymentMapper.toDto(payments.save(p), client.getFullName(), LocalDate.now());
    }

    @Transactional
    public PaymentDto markPaid(Long id) {
        Payment p = owned(id);
        if ("PAID".equals(p.getStatus())) throw new ConflictException("El pago ya está marcado como pagado");
        p.setStatus("PAID");
        p.setPaidDate(LocalDate.now());
        return PaymentMapper.toDto(p, nameOf(p.getClientId()), LocalDate.now());
    }

    @Transactional
    public void delete(Long id) {
        Payment p = owned(id);
        if ("PAID".equals(p.getStatus())) throw new ConflictException("Un pago cobrado no se puede borrar");
        payments.delete(p);
    }

    private Payment owned(Long id) {
        Payment p = payments.findById(id).orElseThrow(() -> new NotFoundException("Pago no encontrado"));
        access.assertOwnerOrAdmin(p.getTrainerId());
        return p;
    }
}
