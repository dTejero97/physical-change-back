-- =====================================================================
-- Physical Change · V22 · El ADMIN es el entrenador de los clientes sin entrenador (PostgreSQL 16) — T227
-- Hasta ahora, un cliente dado de alta por el ADMIN quedaba con trainer_id NULL. Desde T225 el ADMIN ya contaba como su
-- responsable, pero sin trainer_id el cliente no podía escribirle ni dejarle notas en Entreno. Con varios ADMIN, el más antiguo
-- activo (misma regla que AccessService.ownerOf). Si no hay ningún ADMIN activo, no cambia nada.
-- No editar una vez desplegado.
--
-- Reversión manual (deja como estaban los clientes que cambió esta migración):
--   UPDATE app_user SET trainer_id = NULL WHERE id IN (SELECT client_id FROM client_trainer_v22);
--   DROP TABLE client_trainer_v22;
--   DELETE FROM flyway_schema_history WHERE version = '22';
-- =====================================================================

-- Copia de los clientes que se van a cambiar (para la reversión). Se podrá borrar en una migración posterior.
CREATE TABLE client_trainer_v22 (
    client_id  BIGINT    PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    trainer_id BIGINT    NOT NULL REFERENCES app_user (id),
    changed_at TIMESTAMP NOT NULL DEFAULT now()
);

INSERT INTO client_trainer_v22 (client_id, trainer_id)
SELECT c.id, a.id
FROM app_user c
CROSS JOIN (SELECT MIN(id) AS id FROM app_user WHERE role = 'ADMIN' AND active = 1) a
WHERE c.role = 'CLIENT' AND c.trainer_id IS NULL AND a.id IS NOT NULL;

UPDATE app_user c SET trainer_id = v.trainer_id
FROM client_trainer_v22 v
WHERE c.id = v.client_id;
