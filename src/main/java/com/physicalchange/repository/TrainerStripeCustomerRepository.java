package com.physicalchange.repository;

import com.physicalchange.domain.TrainerStripeCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TrainerStripeCustomerRepository extends JpaRepository<TrainerStripeCustomer, TrainerStripeCustomer.Key> {
    List<TrainerStripeCustomer> findByClientId(Long clientId);
        Optional<TrainerStripeCustomer> findByClientIdAndTrainerId(Long clientId, Long trainerId);
}
