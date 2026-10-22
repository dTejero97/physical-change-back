package com.physicalchange.repository;

import com.physicalchange.domain.CommunityGroup;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface CommunityGroupRepository extends JpaRepository<CommunityGroup, Long> {
    List<CommunityGroup> findByTrainerIdOrderByCreatedAtDesc(Long trainerId);
    List<CommunityGroup> findAllByOrderByCreatedAtDesc();

    @Query("select g from CommunityGroup g join g.memberIds m where m = :client order by g.createdAt desc")
    List<CommunityGroup> findByMember(@Param("client") Long clientId);
}
