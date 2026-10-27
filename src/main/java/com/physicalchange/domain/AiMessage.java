package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Mensaje de una conversación. mentions: JSON [{clientId, name}] con los nombres reales (nunca se envían al proveedor). */
@Entity
@Table(name = "AI_MESSAGE")
public class AiMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "CONVERSATION_ID", nullable = false) private Long conversationId;
    @Column(nullable = false, length = 10) private String role;
    @Column(nullable = false, length = 8000) private String text;
    @Column(nullable = false, length = 1000) private String mentions = "[]";
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long c) { this.conversationId = c; }
    public String getRole() { return role; }
    public void setRole(String r) { this.role = r; }
    public String getText() { return text; }
    public void setText(String t) { this.text = t; }
    public String getMentions() { return mentions; }
    public void setMentions(String m) { this.mentions = m; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
