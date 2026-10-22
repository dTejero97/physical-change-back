package com.physicalchange.repository;

import com.physicalchange.domain.ClientInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ClientInvitationRepository extends JpaRepository<ClientInvitation, Long> {
    /** Invitaciones por email pendientes de un entrenador. */
    List<ClientInvitation> findByTrainerIdAndEmailIsNotNullAndAcceptedAtIsNullOrderByInvitedAtDesc(Long trainerId);
    List<ClientInvitation> findByEmailIsNotNullAndAcceptedAtIsNullOrderByInvitedAtDesc();
    Optional<ClientInvitation> findByToken(String token);
    boolean existsByTrainerIdAndEmailIgnoreCaseAndAcceptedAtIsNull(Long trainerId, String email);
    List<ClientInvitation> findByTagId(Long tagId);
}
