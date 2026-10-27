package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.service.ReceiptEvents.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bonos de sesiones (ADR-004 §5): se conceden al pagar un comprobante con productos o planes que los incluyen, se cancelan
 * (lo no usado) al anular un comprobante pagado y se gastan al reservar una sesión de su tipo (primero el que antes caduca).
 * Cancelar la reserva antes de que empiece la sesión devuelve la sesión. Reservar no exige bono.
 * También aplica las etiquetas automáticas de productos y planes al cliente.
 */
@Service
public class BonusService {
    private final ClientBonusRepository bonuses;
    private final BonusRedemptionRepository redemptions;
    private final BonusDefinitionRepository definitions;
    private final ReceiptRepository receipts;
    private final ReceiptLineRepository lines;
    private final ProductRepository products;
    private final SubscriptionPlanRepository plans;
    private final SessionTypeRepository sessionTypes;
    private final ClientProfileService profiles;
    private final AccessService access;

    public BonusService(ClientBonusRepository b, BonusRedemptionRepository r, BonusDefinitionRepository d, ReceiptRepository rc,
                        ReceiptLineRepository l, ProductRepository p, SubscriptionPlanRepository pl, SessionTypeRepository st,
                        ClientProfileService pr, AccessService a) {
        bonuses = b; redemptions = r; definitions = d; receipts = rc; lines = l; products = p; plans = pl; sessionTypes = st; profiles = pr; access = a;
    }

    // ---------- Comprobantes ----------

    @EventListener
    public void onPaid(ReceiptPaidEvent ev) {
        Receipt x = receipts.findById(ev.receiptId()).orElseThrow();
        if (bonuses.existsByReceiptId(x.getId())) return;
        Set<Long> tagIds = new LinkedHashSet<>();
        for (ReceiptLine l : lines.findByReceiptIdOrderByPositionAsc(x.getId())) {
            List<BonusDefinition> defs = List.of();
            if (l.getProductId() != null) {
                defs = definitions.findByProductIdIn(List.of(l.getProductId()));
                products.findById(l.getProductId()).ifPresent(p -> tagIds.addAll(p.getAutoTagIds()));
            } else if (l.getPlanId() != null) {
                defs = definitions.findByPlanIdIn(List.of(l.getPlanId()));
                plans.findById(l.getPlanId()).ifPresent(p -> tagIds.addAll(p.getAutoTagIds()));
            }
            for (BonusDefinition d : defs) {
                ClientBonus b = new ClientBonus();
                b.setTrainerId(x.getTrainerId());
                b.setClientId(x.getClientId());
                b.setSessionTypeId(d.getSessionTypeId());
                b.setReceiptId(x.getId());
                b.setProductName(l.getName());
                b.setTotal((short) Math.min(Short.MAX_VALUE, d.getSessions() * l.getQuantity()));
                b.setExpiresAt(x.getPaidDate().plusDays(d.getValidDays()));
                bonuses.save(b);
            }
        }
        if (!tagIds.isEmpty()) profiles.ensure(x.getClientId()).getTagIds().addAll(tagIds);
    }

    /** Anular un comprobante pagado cancela las sesiones de sus bonos aún no usadas (las usadas se quedan). */
    @EventListener
    public void onVoided(ReceiptVoidedEvent ev) {
        if (!ev.wasPaid()) return;
        LocalDateTime now = LocalDateTime.now();
        for (ClientBonus b : bonuses.findByReceiptId(ev.receiptId())) if (b.getCancelledAt() == null) b.setCancelledAt(now);
    }

    // ---------- Reservas (los llama AgendaEventService dentro de su transacción) ----------

    /** Gasta una sesión si la reserva ocupa plaza, el cliente tiene bono vigente del tipo y aún no se ha canjeado. */
    public void redeem(AgendaBooking booking, AgendaEvent event) {
        if (booking.getId() == null || event.getSessionTypeId() == null) return;
        if (redemptions.findByBookingId(booking.getId()).isPresent()) return;
        List<ClientBonus> usable = bonuses.lockUsable(booking.getClientId(), event.getSessionTypeId(), event.getStartAt().toLocalDate());
        if (usable.isEmpty()) return;
        ClientBonus b = usable.get(0);
        b.setUsed((short) (b.getUsed() + 1));
        BonusRedemption r = new BonusRedemption();
        r.setBonusId(b.getId());
        r.setBookingId(booking.getId());
        redemptions.save(r);
    }

    /** Devuelve la sesión si la reserva deja de ocupar plaza antes de que empiece la sesión. */
    public void release(AgendaBooking booking, AgendaEvent event) {
        if (booking.getId() == null || !event.getStartAt().isAfter(LocalDateTime.now())) return;
        redemptions.findByBookingId(booking.getId()).ifPresent(r -> {
            bonuses.findById(r.getBonusId()).ifPresent(b -> b.setUsed((short) Math.max(0, b.getUsed() - 1)));
            redemptions.delete(r);
            redemptions.flush();
        });
    }

    /** "Bonos canjeados" de las reservas dadas (KPI de la Agenda). */
    public long redeemedIn(Collection<Long> bookingIds) {
        return bookingIds.isEmpty() ? 0 : redemptions.countByBookingIdIn(bookingIds);
    }

    // ---------- Consultas ----------

    @Transactional(readOnly = true)
    public List<ClientBonusDto> ofClient(Long clientId) {
        access.assertCanAccessClient(clientId);
        if (access.current().role() == Role.CLIENT) return mine();
        return toDtos(bonuses.findByClientIdOrderByExpiresAtAsc(clientId), false);
    }

    /** Del cliente autenticado: solo los vigentes. */
    @Transactional(readOnly = true)
    public List<ClientBonusDto> mine() {
        return toDtos(bonuses.findByClientIdOrderByExpiresAtAsc(access.current().id()), true);
    }

    private List<ClientBonusDto> toDtos(List<ClientBonus> list, boolean onlyActive) {
        Map<Long, SessionType> types = sessionTypes.findAllById(list.stream().map(ClientBonus::getSessionTypeId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(SessionType::getId, t -> t));
        LocalDate today = LocalDate.now();
        return list.stream().map(b -> {
            String status = b.getCancelledAt() != null ? "CANCELLED" : b.getUsed() >= b.getTotal() ? "USED"
                    : b.getExpiresAt().isBefore(today) ? "EXPIRED" : "ACTIVE";
            SessionType t = types.get(b.getSessionTypeId());
            return new ClientBonusDto(b.getId(), b.getSessionTypeId(), t == null ? "—" : t.getName(), t == null ? null : t.getColor(),
                    b.getTotal(), b.getUsed(), "ACTIVE".equals(status) ? b.getTotal() - b.getUsed() : 0, b.getExpiresAt(), status,
                    b.getReceiptId(), b.getProductName());
        }).filter(d -> !onlyActive || "ACTIVE".equals(d.status())).toList();
    }
}
