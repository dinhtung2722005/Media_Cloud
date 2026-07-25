package com.cloudmedia.api.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.cloudmedia.api.entity.RevokedToken;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Date;
@Repository
public interface RevokedTokenRepository extends JpaRepository<RevokedToken, Long> {
    boolean existsByToken(String token);
    @Transactional 
    void deleteByExpirationDateBefore(Date now);
}