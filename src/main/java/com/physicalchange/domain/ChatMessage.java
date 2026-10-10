package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "CHAT_MESSAGE")
public class ChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "SENDER_ID", nullable = false) private Long senderId;
    @Column(name = "RECIPIENT_ID", nullable = false) private Long recipientId;
    @Column(nullable = false, length = 2000) private String body;
    @Column(name = "SENT_AT", nullable = false) private LocalDateTime sentAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getSenderId() { return senderId; }
    public void setSenderId(Long s) { this.senderId = s; }
    public Long getRecipientId() { return recipientId; }
    public void setRecipientId(Long r) { this.recipientId = r; }
    public String getBody() { return body; }
    public void setBody(String b) { this.body = b; }
    public LocalDateTime getSentAt() { return sentAt; }
}
