package com.cloudmedia.api.config;

import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.sql.common.SqlStorageProviderFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class JobRunrConfig {

    /**
     * Cấu hình StorageProvider cho JobRunr.
     * Chúng ta sẽ tái sử dụng luôn DataSource (kết nối SQL Server) đã cấu hình 
     * trong application.properties để lưu trữ trạng thái của các tiến trình ngầm.
     */
    @Bean
    public StorageProvider storageProvider(DataSource dataSource, JobMapper jobMapper) {
        // SqlStorageProviderFactory sẽ tự động nhận diện hệ quản trị CSDL (SQL Server)
        // và tự động tạo các bảng cần thiết (ví dụ: jobrunr_jobs) để quản lý hàng đợi.
        StorageProvider storageProvider = SqlStorageProviderFactory
                .using(dataSource);
                
        storageProvider.setJobMapper(jobMapper);
        
        return storageProvider;
    }
}