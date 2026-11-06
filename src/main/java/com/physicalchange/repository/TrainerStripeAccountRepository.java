package com.physicalchange.repository;

import com.physicalchange.domain.TrainerStripeAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TrainerStripeAccountRepository extends JpaRepository<TrainerStripeAccount, Long> {
    Optional<TrainerStripeAccount> findByStripeAccountId(String stripeAccountId);
}
