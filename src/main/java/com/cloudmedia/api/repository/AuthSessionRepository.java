package com.cloudmedia.api.repository;

import com.cloudmedia.api.entity.AuthSession;
import java.util.Date;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {
    boolean existsByIdAndUserIdAndRevokedFalseAndExpiresAtAfter(String id, String userId, Date now);

    @Modifying
    @Transactional
    @Query("update AuthSession s set s.revoked = true where s.id = :id and s.userId = :userId")
    int revoke(@Param("id") String id, @Param("userId") String userId);

    @Transactional
    void deleteByExpiresAtBefore(Date now);
}
