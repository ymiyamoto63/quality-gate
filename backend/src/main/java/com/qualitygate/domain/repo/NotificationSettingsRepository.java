package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.NotificationSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, UUID> {
}
