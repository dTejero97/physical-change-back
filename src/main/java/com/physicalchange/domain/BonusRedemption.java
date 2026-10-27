package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Una sesión de bono gastada en una reserva (una por reserva). Se borra si la reserva se cancela antes de empezar. */
@Entity
@Table(name = "BONUS_REDEMPTION")
public class BonusRedemption {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "BONUS_ID", nullable = false) private Long bonusId;
    @Column(name = "BOOKING_ID", nullable = false, unique = true) private Long bookingId;
    @Column(name = "REDEEMED_AT", nullable = false) private LocalDateTime redeemedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getBonusId() { return bonusId; }
    public void setBonusId(Long b) { this.bonusId = b; }
    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long b) { this.bookingId = b; }
    public LocalDateTime getRedeemedAt() { return redeemedAt; }
}
