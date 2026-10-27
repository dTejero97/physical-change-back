package com.physicalchange.repository;

import com.physicalchange.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByTrainerIdAndArchived(Long trainerId, boolean archived);
}
