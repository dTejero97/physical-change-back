package com.physicalchange.repository;

import com.physicalchange.domain.TemplateItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TemplateItemRepository extends JpaRepository<TemplateItem, Long> {
    List<TemplateItem> findByPackIdOrderByPositionAsc(Long packId);
    List<TemplateItem> findAllByOrderByPackIdAscPositionAsc();
}
