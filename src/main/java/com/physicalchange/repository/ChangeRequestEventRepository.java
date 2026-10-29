package com.physicalchange.repository;

import com.physicalchange.domain.ChangeRequestEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChangeRequestEventRepository extends JpaRepository<ChangeRequestEvent, Long> {
    List<ChangeRequestEvent> findByRequestIdOrderByAtAscIdAsc(Long requestId);
}
