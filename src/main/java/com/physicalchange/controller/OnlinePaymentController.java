package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.payment.PaymentProvider.InvalidSignatureException;
import com.physicalchange.service.OnlinePaymentService;
import com.physicalchange.service.StripeWebhookService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Pagos online con Stripe (docs/api-pagos-stripe.md v0.2.0, ADR-009): cliente, estado para el profesional y webhook. */
@RestController @Tag(name = "Pagos online")
public class OnlinePaymentController {
    private static final Logger log = LoggerFactory.getLogger(OnlinePaymentController.class);
    static final int MAX_WEBHOOK_BYTES = 1024 * 1024;
    private final OnlinePaymentService svc;
    private final StripeWebhookService webhook;
    public OnlinePaymentController(OnlinePaymentService s, StripeWebhookService w) { svc = s; webhook = w; }

    // ---- Cliente ----
    @PostMapping("/api/me/receipts/{id}/checkout") @PreAuthorize("hasRole('CLIENT')") @ResponseStatus(HttpStatus.CREATED)
    public CheckoutDto payReceipt(@PathVariable Long id) { return svc.payReceipt(id); }

    @GetMapping("/api/me/plans") @PreAuthorize("hasRole('CLIENT')")
    public List<ClientPlanDto> plans() { return svc.plans(); }

    @PostMapping("/api/me/plans/{id}/checkout") @PreAuthorize("hasRole('CLIENT')") @ResponseStatus(HttpStatus.CREATED)
    public CheckoutDto subscribe(@PathVariable Long id) { return svc.subscribe(id); }

    @GetMapping("/api/me/orders") @PreAuthorize("hasRole('CLIENT')")
    public List<OrderDto> orders() { return svc.myOrders(); }

    @GetMapping("/api/me/orders/{id}") @PreAuthorize("hasRole('CLIENT')")
    public OrderDto order(@PathVariable Long id) { return svc.myOrder(id); }

    @GetMapping("/api/me/subscriptions") @PreAuthorize("hasRole('CLIENT')")
    public List<MySubscriptionDto> subscriptions() { return svc.mySubscriptions(); }

    @PostMapping("/api/me/billing-portal") @PreAuthorize("hasRole('CLIENT')")
    public PortalDto portal() { return svc.billingPortal(); }

    // ---- ¿Están activados los pagos? El cliente lo usa para mostrar "Pagar". Nunca devuelve claves ----
    @GetMapping("/api/payments/stripe/status") @PreAuthorize("hasAnyRole('ADMIN','TRAINER','CLIENT')")
    public PaymentsStatusDto status() { return svc.status(); }

    // ---- Webhook (público; la firma es la autenticación) ----
    @PostMapping("/api/public/stripe/webhook")
    public ResponseEntity<Void> webhook(@RequestBody byte[] payload, @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        // T130: los eventos de Stripe ocupan pocos KB; un cuerpo enorme se rechaza antes de calcular la firma.
        if (payload.length > MAX_WEBHOOK_BYTES) return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        var event = verified(payload, signature);
        if (event == null) return ResponseEntity.badRequest().build();
        webhook.apply(event);
        return ResponseEntity.ok().build();
    }

    private com.fasterxml.jackson.databind.JsonNode verified(byte[] payload, String signature) {
        try {
            return webhook.verify(payload, signature);
        } catch (InvalidSignatureException e) {
            log.warn("Stripe: webhook rechazado ({})", e.getMessage());
            return null;
        }
    }
}
