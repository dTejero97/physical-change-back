package com.physicalchange.repository;

import com.physicalchange.domain.ContentPostMetric;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ContentPostMetricRepository extends JpaRepository<ContentPostMetric, Long> {
    List<ContentPostMetric> findByPostIdOrderByMeasuredOnDescIdDesc(Long postId);
    Optional<ContentPostMetric> findByPostIdAndNetworkAndMeasuredOnAndSource(Long postId, String network, LocalDate on, String source);
    boolean existsByPostIdAndNetworkAndMeasuredOn(Long postId, String network, LocalDate on);

    /** Al borrar la publicación (en PostgreSQL también ON DELETE CASCADE). */
    @Modifying
    @Query("delete from ContentPostMetric m where m.postId = :post")
    int deleteByPost(@Param("post") Long postId);

    @Query("select distinct m.postId from ContentPostMetric m where m.postId in :posts")
    List<Long> postsWithMetrics(@Param("posts") Collection<Long> postIds);

    @Query("select m from ContentPostMetric m where m.postId in :posts and m.measuredOn <= :until")
    List<ContentPostMetric> forPosts(@Param("posts") Collection<Long> postIds, @Param("until") LocalDate until);
}
