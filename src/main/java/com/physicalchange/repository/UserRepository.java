package com.physicalchange.repository;

import com.physicalchange.domain.AppUser;
import com.physicalchange.domain.Role;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmailIgnoreCase(String email);
    Page<AppUser> findByRoleAndTrainerId(Role role, Long trainerId, Pageable p);
    List<AppUser> findByRoleAndTrainerId(Role role, Long trainerId);
    Page<AppUser> findByRole(Role role, Pageable p);
}
