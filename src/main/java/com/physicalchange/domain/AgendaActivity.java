package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Acción registrada en la agenda. Guarda una copia del evento para sobrevivir a su borrado. */
@Entity
@Table(name = "AGENDA_ACTIVITY")
public class AgendaActivity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(name = "ACTOR_ID", nullable = false) private Long actorId;
    @Column(name = "ACTOR_NAME", nullable = false, length = 120) private String actorName;
    @Column(name = "ACTOR_KIND", nullable = false, length = 10) private String actorKind;
    @Column(nullable = false, length = 20) private String action;
    @Column(name = "EVENT_TITLE", nullable = false, length = 120) private String eventTitle;
    @Column(name = "EVENT_EMOJI", length = 16) private String eventEmoji;
    @Column(name = "EVENT_START", nullable = false) private LocalDateTime eventStart;
    @Column(nullable = false) private boolean recurrent;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long a) { this.actorId = a; }
    public String getActorName() { return actorName; }
    public void setActorName(String n) { this.actorName = n; }
    public String getActorKind() { return actorKind; }
    public void setActorKind(String k) { this.actorKind = k; }
    public String getAction() { return action; }
    public void setAction(String a) { this.action = a; }
    public String getEventTitle() { return eventTitle; }
    public void setEventTitle(String t) { this.eventTitle = t; }
    public String getEventEmoji() { return eventEmoji; }
    public void setEventEmoji(String e) { this.eventEmoji = e; }
    public LocalDateTime getEventStart() { return eventStart; }
    public void setEventStart(LocalDateTime s) { this.eventStart = s; }
    public boolean isRecurrent() { return recurrent; }
    public void setRecurrent(boolean r) { this.recurrent = r; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
