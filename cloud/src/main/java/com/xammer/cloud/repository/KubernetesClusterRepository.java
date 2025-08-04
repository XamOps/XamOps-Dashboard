package com.xammer.cloud.repository;

import com.xammer.cloud.domain.KubernetesCluster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KubernetesClusterRepository extends JpaRepository<KubernetesCluster, Long> {

    /**
     * Finds all Kubernetes clusters associated with a specific CloudAccount ID.
     * @param cloudAccountId The ID of the CloudAccount.
     * @return A list of associated KubernetesCluster entities.
     */
    List<KubernetesCluster> findByCloudAccountId(Long cloudAccountId);
}