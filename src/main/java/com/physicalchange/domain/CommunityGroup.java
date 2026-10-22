package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** Grupo de comunidad de un entrenador con sus clientes miembros. */
@Entity
@Table(name = "COMMUNITY_GROUP")
public class CommunityGroup {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 80) private String name;
    @Column(length = 500) private String description;
    @Column(length = 16) private String emoji;
    @Column(name = "WHO_CAN_POST", nullable = false, length = 10) private String whoCanPost = "EVERYONE";
    @Column(name = "SHARE_RESULTS", nullable = false) private boolean shareResults = true;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    @ElementCollection
    @CollectionTable(name = "COMMUNITY_MEMBER", joinColumns = @JoinColumn(name = "GROUP_ID"))
    @Column(name = "CLIENT_ID")
    private Set<Long> memberIds = new HashSet<>();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getEmoji() { return emoji; }
    public void setEmoji(String e) { this.emoji = e; }
    public String getWhoCanPost() { return whoCanPost; }
    public void setWhoCanPost(String w) { this.whoCanPost = w; }
    public boolean isShareResults() { return shareResults; }
    public void setShareResults(boolean s) { this.shareResults = s; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Set<Long> getMemberIds() { return memberIds; }
}
