package com.physicalchange.repository;

import com.physicalchange.domain.CommunityPost;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, Long> {
    List<CommunityPost> findByGroupIdOrderByCreatedAtDesc(Long groupId);
}
