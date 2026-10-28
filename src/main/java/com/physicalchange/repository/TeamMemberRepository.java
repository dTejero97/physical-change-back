package com.physicalchange.repository;

import com.physicalchange.domain.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {
    List<TeamMember> findByOwnerIdAndRemovedAtIsNullOrderByInvitedAtDesc(Long ownerId);
}
