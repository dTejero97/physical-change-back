package com.physicalchange.repository;

import com.physicalchange.domain.AppUser;
import com.physicalchange.domain.StoredFile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    /** Bytes ocupados por un propietario, incluidas las subidas pendientes (cuota, ADR-001/005). */
    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.ownerId = :o")
    long usedBytes(@Param("o") Long ownerId);

    /** Bytes ocupados en un almacenamiento (tope global de R2, ADR-005). */
    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.storage = :s")
    long usedBytesIn(@Param("s") String storage);

    /** Bloquea la fila del propietario hasta el fin de la transacción: serializa la comprobación de cuota. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    AppUser lockOwner(@Param("id") Long ownerId);

    /** Subidas directas abandonadas, de cualquier propietario (purga por lotes). */
    List<StoredFile> findTop50ByStatusAndCreatedAtBefore(String status, LocalDateTime before);
}
