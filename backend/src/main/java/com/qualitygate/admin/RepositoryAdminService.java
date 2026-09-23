package com.qualitygate.admin;

import com.qualitygate.admin.dto.RepositoryRequests.CreateRepositoryRequest;
import com.qualitygate.admin.dto.RepositoryRequests.DefineComponentRequest;
import com.qualitygate.admin.dto.RepositoryRequests.UpdateRepositoryRequest;
import com.qualitygate.domain.entity.IngestToken;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.RepositoryComponent;
import com.qualitygate.domain.repo.IngestTokenRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RepositoryComponentRepository;
import com.qualitygate.ingest.security.IngestTokenAuthenticationFilter;
import com.qualitygate.platform.audit.AuditAction;
import com.qualitygate.platform.audit.AuditLogger;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.security.Actor;
import com.qualitygate.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * リポジトリの登録・コンポーネント定義・Ingest Token の発行と失効（S-08 / FR-01）。
 * 変更はすべて監査ログに残す（FR-14-1）。
 */
@Service
public class RepositoryAdminService {

    private static final String TARGET_REPOSITORY = "REPOSITORY";
    private static final String TARGET_TOKEN = "INGEST_TOKEN";

    /** トークンの文字種。紛らわしい文字も含めるが、人が読み上げる前提ではない。 */
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int PREFIX_LENGTH = 8;
    private static final int SECRET_LENGTH = 32;

    private final SecureRandom random = new SecureRandom();
    private final MonitoredRepositoryRepository repositories;
    private final RepositoryComponentRepository components;
    private final IngestTokenRepository tokens;
    private final CurrentUser currentUser;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public RepositoryAdminService(MonitoredRepositoryRepository repositories,
                                  RepositoryComponentRepository components,
                                  IngestTokenRepository tokens, CurrentUser currentUser,
                                  AuditLogger auditLogger, ObjectMapper objectMapper) {
        this.repositories = repositories;
        this.components = components;
        this.tokens = tokens;
        this.currentUser = currentUser;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MonitoredRepository create(CreateRepositoryRequest request) {
        Actor actor = currentUser.actor();
        // GitHub の owner / name は大文字小文字を区別しない。別物として登録させない
        if (repositories.findByOwnerIgnoreCaseAndNameIgnoreCase(request.owner(), request.name())
                .isPresent()) {
            throw new ApiException(ErrorCode.REPOSITORY_ALREADY_EXISTS,
                    "%s/%s は既に登録されています".formatted(request.owner(), request.name()));
        }
        MonitoredRepository repository = new MonitoredRepository(Uuid7.generate(),
                request.owner(), request.name(), actor.userId());
        if (request.defaultBranch() != null && !request.defaultBranch().isBlank()) {
            repository.setDefaultBranch(request.defaultBranch().strip());
        }
        if (request.measurePullRequests() != null) {
            repository.setMeasurePullRequests(request.measurePullRequests());
        }
        repositories.save(repository);
        auditLogger.record(actor, AuditAction.REPOSITORY_CREATED, TARGET_REPOSITORY,
                repository.getId(), null, snapshot(repository));
        return repository;
    }

    @Transactional
    public MonitoredRepository update(UUID repositoryId, UpdateRepositoryRequest request) {
        Actor actor = currentUser.actor();
        MonitoredRepository repository = load(repositoryId);
        Map<String, Object> before = snapshot(repository);
        if (request.defaultBranch() != null) {
            repository.setDefaultBranch(request.defaultBranch().strip());
        }
        if (request.measurePullRequests() != null) {
            repository.setMeasurePullRequests(request.measurePullRequests());
        }
        if (request.enabled() != null) {
            repository.setEnabled(request.enabled());
        }
        Map<String, Object> after = snapshot(repository);
        if (!before.equals(after)) {
            auditLogger.record(actor, AuditAction.REPOSITORY_UPDATED, TARGET_REPOSITORY,
                    repositoryId, before, after);
        }
        return repository;
    }

    /** コンポーネントを定義する。同じ名前があれば定義を置き換える。 */
    @Transactional
    public RepositoryComponent defineComponent(UUID repositoryId, DefineComponentRequest request) {
        Actor actor = currentUser.actor();
        load(repositoryId);
        String patterns = objectMapper.writeValueAsString(request.pathPatterns());
        RepositoryComponent component = components
                .findByRepositoryIdAndName(repositoryId, request.name())
                .map(existing -> {
                    existing.redefine(request.language(), patterns);
                    return existing;
                })
                .orElseGet(() -> components.save(new RepositoryComponent(Uuid7.generate(),
                        repositoryId, request.name(), request.language(), patterns,
                        (int) components.countByRepositoryId(repositoryId))));
        auditLogger.record(actor, AuditAction.COMPONENT_DEFINED, TARGET_REPOSITORY, repositoryId,
                null, Map.of("name", request.name(), "language", request.language(),
                        "pathPatterns", request.pathPatterns()));
        return component;
    }

    @Transactional(readOnly = true)
    public List<IngestToken> tokensOf(UUID repositoryId) {
        load(repositoryId);
        return tokens.findByRepositoryIdOrderByCreatedAtDesc(repositoryId);
    }

    /**
     * トークンを発行する。平文は戻り値でのみ返し、保存するのはハッシュだけにする。
     *
     * <p>形式は {@code qg_<8 文字の prefix>_<32 文字の秘密>}（docs/05-architecture.md 8.3）。
     * prefix は検索用の公開値で、一意制約に当たれば引き直す。
     */
    @Transactional
    public Issued issueToken(UUID repositoryId, String description) {
        Actor actor = currentUser.actor();
        load(repositoryId);
        String prefix;
        do {
            prefix = randomString(PREFIX_LENGTH);
        } while (tokens.findByTokenPrefix(prefix).isPresent());
        String plain = IngestTokenAuthenticationFilter.TOKEN_PREFIX_MARKER + prefix + "_"
                + randomString(SECRET_LENGTH);

        IngestToken token = tokens.save(new IngestToken(Uuid7.generate(), repositoryId, prefix,
                IngestTokenAuthenticationFilter.sha256(plain),
                description == null || description.isBlank() ? null : description.strip(),
                actor.userId()));
        // 監査ログにも平文は残さない。残すのは検索用の prefix だけ
        auditLogger.record(actor, AuditAction.INGEST_TOKEN_ISSUED, TARGET_TOKEN, token.getId(),
                null, Map.of("repositoryId", repositoryId.toString(), "tokenPrefix", prefix));
        return new Issued(token, plain);
    }

    /** 失効は即座に効く。既に失効していれば何もしない（冪等）。 */
    @Transactional
    public void revokeToken(UUID tokenId) {
        Actor actor = currentUser.actor();
        IngestToken token = tokens.findById(tokenId)
                .orElseThrow(() -> ApiException.notFound("Ingest Token", tokenId));
        if (token.isRevoked()) {
            return;
        }
        token.revoke(Instant.now());
        auditLogger.record(actor, AuditAction.INGEST_TOKEN_REVOKED, TARGET_TOKEN, tokenId,
                null, Map.of("repositoryId", token.getRepositoryId().toString(),
                        "tokenPrefix", token.getTokenPrefix()));
    }

    private MonitoredRepository load(UUID repositoryId) {
        return repositories.findById(repositoryId)
                .orElseThrow(() -> ApiException.notFound("リポジトリ", repositoryId));
    }

    private String randomString(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(chars);
    }

    private static Map<String, Object> snapshot(MonitoredRepository repository) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fullName", repository.fullName());
        values.put("defaultBranch", repository.getDefaultBranch());
        values.put("measurePullRequests", repository.isMeasurePullRequests());
        values.put("enabled", repository.isEnabled());
        return values;
    }

    /** @param plainToken 平文。呼び出し側は応答に載せたら保持しない */
    public record Issued(IngestToken token, String plainToken) {
    }
}
