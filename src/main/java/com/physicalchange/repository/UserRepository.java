package com.physicalchange.repository;

import com.physicalchange.domain.AppUser;
import com.physicalchange.domain.Role;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmailIgnoreCase(String email);
    Page<AppUser> findByRoleAndTrainerId(Role role, Long trainerId, Pageable p);
    List<AppUser> findByRoleAndTrainerId(Role role, Long trainerId);
    Page<AppUser> findByRole(Role role, Pageable p);
    List<AppUser> findByRole(Role role);

    /** Clientes sin borrado lógico (CLIENT_PROFILE.REMOVED_AT nulo o sin ficha). */
    @Query("select u from AppUser u where u.role = com.physicalchange.domain.Role.CLIENT and u.trainerId = :t and not exists " +
           "(select p.clientId from ClientProfile p where p.clientId = u.id and p.removedAt is not null)")
    Page<AppUser> findVisibleClients(@Param("t") Long trainerId, Pageable p);

    @Query("select u from AppUser u where u.role = com.physicalchange.domain.Role.CLIENT and not exists " +
           "(select p.clientId from ClientProfile p where p.clientId = u.id and p.removedAt is not null)")
    Page<AppUser> findAllVisibleClients(Pageable p);
}
