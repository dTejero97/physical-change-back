package com.physicalchange.service;

import com.physicalchange.domain.Coupon;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.CouponRepository;
import com.physicalchange.security.AccessService;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/** Cupones de descuento (ADR-004 §3). Código único por profesional y en mayúsculas; el uso lo descuenta ReceiptService. */
@Service
public class CouponService {
    private final CouponRepository coupons;
    private final AccessService access;

    public CouponService(CouponRepository c, AccessService a) { coupons = c; access = a; }

    @Transactional(readOnly = true)
    public Page<CouponDto> list(String q, Pageable p) {
        String n = q == null ? "" : q.strip().toUpperCase(Locale.ROOT);
        List<CouponDto> all = coupons.findByTrainerIdOrderByCreatedAtDesc(access.current().id()).stream()
                .filter(c -> n.isEmpty() || c.getCode().contains(n) || (c.getDescription() != null && c.getDescription().toUpperCase(Locale.ROOT).contains(n)))
                .map(CouponService::toDto).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Sort.Order o = p.getSort().stream().findFirst().orElse(null);
        if (o != null) {
            Comparator<CouponDto> c = switch (o.getProperty()) {
                case "uses" -> Comparator.comparingInt(CouponDto::uses);
                case "expiresAt" -> Comparator.comparing(CouponDto::expiresAt, Comparator.nullsLast(Comparator.naturalOrder()));
                case "active" -> Comparator.comparing(CouponDto::active);
                default -> Comparator.comparing(CouponDto::code);
            };
            all.sort(o.isDescending() ? c.reversed() : c);
        }
        int a = (int) Math.min(p.getOffset(), all.size());
        return new PageImpl<>(all.subList(a, Math.min(a + p.getPageSize(), all.size())), p, all.size());
    }

    @Transactional
    public CouponDto create(CouponRequest r) {
        Long me = access.current().id();
        String code = code(r.code());
        if (coupons.existsByTrainerIdAndCode(me, code)) throw new ConflictException("Ya existe un cupón con este código");
        Coupon c = new Coupon();
        c.setTrainerId(me);
        apply(c, r, code);
        return toDto(coupons.save(c));
    }

    @Transactional
    public CouponDto update(Long id, CouponRequest r) {
        Coupon c = owned(id);
        String code = code(r.code());
        if (coupons.existsByTrainerIdAndCodeAndIdNot(c.getTrainerId(), code, id)) throw new ConflictException("Ya existe un cupón con este código");
        if (r.maxUses() != null && r.maxUses() < c.getUses())
            throw new ConflictException("Ya se ha usado " + c.getUses() + " veces: el máximo no puede ser menor");
        apply(c, r, code);
        return toDto(c);
    }

    @Transactional
    public void delete(Long id) {
        Coupon c = owned(id);
        if (c.getUses() > 0) throw new ConflictException("Este cupón ya se ha usado: desactívalo en lugar de borrarlo");
        coupons.delete(c);
    }

    private void apply(Coupon c, CouponRequest r, String code) {
        if ("PERCENT".equals(r.type()) && r.value().compareTo(java.math.BigDecimal.valueOf(100)) > 0)
            throw new BadRequestException("Un porcentaje no puede pasar del 100 %");
        c.setCode(code);
        c.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().strip());
        c.setType(r.type());
        c.setValue(r.value().setScale(2, RoundingMode.HALF_UP));
        c.setAppliesTo(r.appliesTo());
        c.setMaxUses(r.maxUses());
        c.setExpiresAt(r.expiresAt());
        c.setActive(r.active());
    }

    private Coupon owned(Long id) {
        return coupons.findById(id).filter(c -> c.getTrainerId().equals(access.current().id()))
                .orElseThrow(() -> new NotFoundException("Cupón no encontrado"));
    }

    private static String code(String raw) {
        String code = raw.strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9_-]{3,30}")) throw new BadRequestException("Código no válido: 3 a 30 letras, números, - o _");
        return code;
    }

    static String status(Coupon c) {
        if (!c.isActive()) return "INACTIVE";
        if (c.getExpiresAt() != null && c.getExpiresAt().isBefore(LocalDate.now())) return "EXPIRED";
        if (c.getMaxUses() != null && c.getUses() >= c.getMaxUses()) return "EXHAUSTED";
        return "ACTIVE";
    }

    private static CouponDto toDto(Coupon c) {
        return new CouponDto(c.getId(), c.getCode(), c.getDescription(), c.getType(), c.getValue(), c.getAppliesTo(), c.getMaxUses(),
                c.getUses(), c.getExpiresAt(), c.isActive(), status(c), c.getCreatedAt());
    }
}
