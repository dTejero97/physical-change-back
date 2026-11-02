package com.physicalchange.repository;

import com.physicalchange.domain.LoadingBackgroundSetting;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface LoadingBackgroundSettingRepository extends JpaRepository<LoadingBackgroundSetting, Short> {
    /** Bloquea la fila del fondo activo: serializa "cambiar fondo" y "eliminar" (no se borra el que pasa a ser activo). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from LoadingBackgroundSetting s where s.id = 1")
    Optional<LoadingBackgroundSetting> lockSingleton();
}
