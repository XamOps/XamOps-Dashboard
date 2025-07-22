package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {

    /**
     * Finds a cloud account by its unique external ID.
     * Spring Data JPA automatically implements this method based on its name.
     *
     * @param externalId The external ID to search for.
     * @return An Optional containing the found CloudAccount, or empty if not found.
     */
    Optional<CloudAccount> findByExternalId(String externalId);

    /**
     * Finds a cloud account by its AWS account ID.
     * Spring Data JPA automatically implements this method based on its name.
     *
     * @param awsAccountId The AWS account ID to search for.
     * @return An Optional containing the found CloudAccount, or empty if not found.
     */
    Optional<CloudAccount> findByAwsAccountId(String awsAccountId);

    /**
     * Finds all cloud accounts associated with a specific client.
     *
     * @param clientId The ID of the client.
     * @return A list of CloudAccounts for the given client.
     */
    List<CloudAccount> findByClientId(Long clientId);

    /**
     * Finds a cloud account by its AWS account ID and client ID.
     *
     * @param awsAccountId The AWS account ID to search for.
     * @param clientId The ID of the client.
     * @return An Optional containing the found CloudAccount, or empty if not found.
     */
    Optional<CloudAccount> findByAwsAccountIdAndClientId(String awsAccountId, Long clientId);
}
