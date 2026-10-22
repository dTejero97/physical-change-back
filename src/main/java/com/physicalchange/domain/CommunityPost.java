package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** Publicación en un grupo; puede compartir un workout del entrenador. */
@Entity
@Table(name = "COMMUNITY_POST")
public class CommunityPost {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "GROUP_ID", nullable = false) private Long groupId;
    @Column(name = "AUTHOR_ID", nullable = false) private Long authorId;
    @Column(nullable = false, length = 2000) private String body;
    @Column(name = "WORKOUT_ID") private Long workoutId;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    @ElementCollection
    @CollectionTable(name = "COMMUNITY_LIKE", joinColumns = @JoinColumn(name = "POST_ID"))
    @Column(name = "USER_ID")
    private Set<Long> likedBy = new HashSet<>();

    public Long getId() { return id; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long g) { this.groupId = g; }
    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long a) { this.authorId = a; }
    public String getBody() { return body; }
    public void setBody(String b) { this.body = b; }
    public Long getWorkoutId() { return workoutId; }
    public void setWorkoutId(Long w) { this.workoutId = w; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Set<Long> getLikedBy() { return likedBy; }
}
