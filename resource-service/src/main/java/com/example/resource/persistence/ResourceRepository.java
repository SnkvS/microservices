package com.example.resource.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceRepository extends JpaRepository<ResourceEntity, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from ResourceEntity r where r.id=:id")
    java.util.Optional<ResourceEntity> findForUpdate(@org.springframework.data.repository.query.Param("id") long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @org.springframework.data.jpa.repository.Query("select r from ResourceEntity r where r.id=:id")
    java.util.Optional<ResourceEntity> findForRead(@org.springframework.data.repository.query.Param("id") long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from ResourceEntity r where r.id in :ids order by r.id")
    java.util.List<ResourceEntity> findAllForUpdate(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
