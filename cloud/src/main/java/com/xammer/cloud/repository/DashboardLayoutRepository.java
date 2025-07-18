package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DashboardLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DashboardLayoutRepository extends JpaRepository<DashboardLayout, String> {
}
