package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByGithubLoginIgnoreCase(String githubLogin);

    Optional<UserAccount> findByGithubUserId(Long githubUserId);
}
