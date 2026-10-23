package com.physicalchange.repository;

import com.physicalchange.domain.TemplatePack;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TemplatePackRepository extends JpaRepository<TemplatePack, Long> {
    List<TemplatePack> findAllByOrderByPositionAsc();
}
