package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Servicios que mantienen la plataforma (Render, Vercel, Cloudflare…) y sus pagos (ADR-004 §6). Solo ADMIN: lo comprueba el
 * controlador. El justificante del proveedor es un archivo DOCUMENT propio de FileService (R2 si está activo).
 */
@Service
public class PlatformAdminService {
    private final PlatformServiceEntryRepository services;
    private final PlatformPaymentRepository payments;
    private final FileService files;
    private final StoredFileRepository storedFiles;

    public PlatformAdminService(PlatformServiceEntryRepository s, PlatformPaymentRepository p, FileService f, StoredFileRepository sf) {
        services = s; payments = p; files = f; storedFiles = sf;
    }

    // ---------- Servicios ----------

    @Transactional(readOnly = true)
    public PlatformServicesDto list() {
        List<PlatformPayment> all = payments.findAllByOrderByPaymentDateDesc();
        Map<Long, Long> count = all.stream().collect(Collectors.groupingBy(PlatformPayment::getServiceId, Collectors.counting()));
        Map<Long, LocalDate> last = new HashMap<>();
        for (PlatformPayment p : all) last.putIfAbsent(p.getServiceId(), p.getPaymentDate());
        List<PlatformServiceDto> list = services.findAll().stream().sorted(Comparator.comparing(PlatformServiceEntry::getId))
                .map(s -> toDto(s, count.getOrDefault(s.getId(), 0L), last.get(s.getId()))).toList();
        BigDecimal monthly = list.stream().filter(PlatformServiceDto::active).map(PlatformServiceDto::monthlyCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PlatformServicesDto(list, monthly, monthly.multiply(BigDecimal.valueOf(12)));
    }

    @Transactional
    public PlatformServiceDto create(PlatformServiceRequest r) {
        if (services.findAll().stream().anyMatch(s -> s.getName().equalsIgnoreCase(r.name().strip())))
            throw new ConflictException("Ya existe un servicio con ese nombre");
        PlatformServiceEntry s = new PlatformServiceEntry();
        apply(s, r);
        return toDto(services.save(s), 0, null);
    }

    @Transactional
    public PlatformServiceDto update(Long id, PlatformServiceRequest r) {
        PlatformServiceEntry s = service(id);
        if (services.findAll().stream().anyMatch(o -> !o.getId().equals(id) && o.getName().equalsIgnoreCase(r.name().strip())))
            throw new ConflictException("Ya existe un servicio con ese nombre");
        apply(s, r);
        return list().services().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    /** Con pagos registrados no se borra: hay que desactivarlo. */
    @Transactional
    public void delete(Long id) {
        PlatformServiceEntry s = service(id);
        if (payments.existsByServiceId(id)) throw new ConflictException("Tiene pagos registrados: desactívalo en lugar de borrarlo");
        services.delete(s);
    }

    private void apply(PlatformServiceEntry s, PlatformServiceRequest r) {
        s.setName(r.name().strip());
        s.setPurpose(blank(r.purpose()));
        s.setPlan(r.plan().strip());
        s.setMonthlyCost(r.monthlyCost().setScale(2, RoundingMode.HALF_UP));
        s.setRenewalDate(r.renewalDate());
        s.setDashboardUrl(blank(r.dashboardUrl()));
        s.setFreeLimits(blank(r.freeLimits()));
        s.setActive(r.active());
    }

    private PlatformServiceEntry service(Long id) {
        return services.findById(id).orElseThrow(() -> new NotFoundException("Servicio no encontrado"));
    }

    private static PlatformServiceDto toDto(PlatformServiceEntry s, long count, LocalDate last) {
        return new PlatformServiceDto(s.getId(), s.getName(), s.getPurpose(), s.getPlan(), s.getMonthlyCost(), s.getRenewalDate(),
                s.getDashboardUrl(), s.getFreeLimits(), s.isActive(), count, last);
    }

    // ---------- Pagos ----------

    @Transactional(readOnly = true)
    public Page<PlatformPaymentDto> payments(LocalDate from, LocalDate to, Long serviceId, String status, Pageable p) {
        LocalDate[] r = ReceiptService.range(from, to);
        List<PlatformPayment> list = payments.findByPaymentDateBetweenOrderByPaymentDateDescIdDesc(r[0], r[1]).stream()
                .filter(x -> serviceId == null || x.getServiceId().equals(serviceId))
                .filter(x -> status == null || x.getStatus().equals(status)).toList();
        int a = (int) Math.min(p.getOffset(), list.size());
        return new PageImpl<>(toDtos(list.subList(a, Math.min(a + p.getPageSize(), list.size()))), p, list.size());
    }

    @Transactional
    public PlatformPaymentDto createPayment(PlatformPaymentRequest r) {
        PlatformPayment x = new PlatformPayment();
        applyPayment(x, r);
        return toDtos(List.of(payments.save(x))).get(0);
    }

    @Transactional
    public PlatformPaymentDto updatePayment(Long id, PlatformPaymentRequest r) {
        PlatformPayment x = payment(id);
        Long oldFile = x.getFileId();
        applyPayment(x, r);
        if (oldFile != null && !oldFile.equals(x.getFileId())) files.removeById(oldFile);
        return toDtos(List.of(x)).get(0);
    }

    /** Borra también el justificante. */
    @Transactional
    public void deletePayment(Long id) {
        PlatformPayment x = payment(id);
        payments.delete(x);
        files.removeById(x.getFileId());
    }

    private void applyPayment(PlatformPayment x, PlatformPaymentRequest r) {
        service(r.serviceId());
        if (r.fileId() != null) files.owned(r.fileId(), "DOCUMENT");
        x.setServiceId(r.serviceId());
        x.setPaymentDate(r.date());
        x.setConcept(r.concept().strip());
        x.setAmount(r.amount().setScale(2, RoundingMode.HALF_UP));
        x.setStatus(r.status());
        x.setFileId(r.fileId());
        x.setNotes(blank(r.notes()));
    }

    private PlatformPayment payment(Long id) {
        return payments.findById(id).orElseThrow(() -> new NotFoundException("Pago no encontrado"));
    }

    private List<PlatformPaymentDto> toDtos(List<PlatformPayment> list) {
        Map<Long, String> names = services.findAll().stream().collect(Collectors.toMap(PlatformServiceEntry::getId, PlatformServiceEntry::getName));
        return list.stream().map(x -> {
            String fileName = null, fileUrl = null;
            if (x.getFileId() != null) {
                Optional<StoredFile> f = storedFiles.findById(x.getFileId());
                fileName = f.map(StoredFile::getName).orElse(null);
                fileUrl = f.isPresent() ? files.url(x.getFileId()) : null;
            }
            return new PlatformPaymentDto(x.getId(), x.getServiceId(), names.getOrDefault(x.getServiceId(), "—"), x.getPaymentDate(), x.getConcept(),
                    x.getAmount(), x.getStatus(), x.getFileId(), fileName, fileUrl, x.getNotes(), x.getCreatedAt());
        }).toList();
    }


    private static String blank(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
