package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {
    java.util.Optional<CloudAccount> findByExternalId(String externalId);
}