// package com.cloudmedia.api.config;

// import org.jobrunr.storage.StorageProvider;
// import org.jobrunr.storage.sql.sqlserver.SqlServerStorageProvider;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;

// import javax.sql.DataSource;

// @Configuration
// public class JobRunrConfig {

//     @Bean
//     public StorageProvider storageProvider(DataSource dataSource) {
//         // Chỉ định JobRunr sử dụng SQL Server DataSource của Spring Boot
//         SqlServerStorageProvider storageProvider = new SqlServerStorageProvider(dataSource);
        
//         // Bật tính năng tự động tạo các bảng cần thiết của JobRunr trong Database
//         storageProvider.setJobStorageCreatorEnabled(true); 
        
//         return storageProvider;
//     }
// }