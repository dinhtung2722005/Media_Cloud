package com.cloudmedia.api.repository;

import com.cloudmedia.api.entity.StorageIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageIdentityRepository extends JpaRepository<StorageIdentity, String> {}
