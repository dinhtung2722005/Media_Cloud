package com.cloudmedia.api.service;

import com.cloudmedia.api.repository.RevokedTokenRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class TokenCleanupService {

    private final RevokedTokenRepository revokedTokenRepository;

    public TokenCleanupService(RevokedTokenRepository revokedTokenRepository) {
        this.revokedTokenRepository = revokedTokenRepository;
    }

    
    @Scheduled(cron = "0 0 0 * * ?")
    public void cleanUpExpiredTokens() {
        System.out.println("Bắt đầu dọn dẹp các Token đã hết hạn trong Database...");
        
        revokedTokenRepository.deleteByExpirationDateBefore(new Date());
        
        System.out.println("Đã dọn dẹp xong Token rác!");
    }
}