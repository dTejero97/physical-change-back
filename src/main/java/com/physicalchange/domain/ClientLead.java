package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Persona interesada en entrenar con el profesional (formulario web, recomendación u otros). */
@Entity
@Table(name = "CLIENT_LEAD")
public class ClientLead {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 120) private String name;
    @Column(length = 160) private String email;
    @Column(length = 40) private String phone;
    @Column(length = 2000) private String message;
    @Column(nullable = false, length = 20) private String source;
    @Column(name = "REGISTERED_AT", nullable = false) private LocalDateTime registeredAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getEmail() { return email; }
    public void setEmail(String e) { this.email = e; }
    public String getPhone() { return phone; }
    public void setPhone(String p) { this.phone = p; }
    public String getMessage() { return message; }
    public void setMessage(String m) { this.message = m; }
    public String getSource() { return source; }
    public void setSource(String s) { this.source = s; }
    public LocalDateTime getRegisteredAt() { return registeredAt; }
}
