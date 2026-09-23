-- 通知設定（FR-11-2 / FR-11-3）。リポジトリごとに 1 行。
--
-- Slack の Incoming Webhook URL はそれ自体が投稿の権限を持つ秘密情報であるため、
-- アプリ側で暗号化した値（AES-GCM）だけを保存する（docs/01-requirements.md 7.4）。
-- 画面と API には末尾だけを見せたマスク表示を返し、平文は二度と返さない。
CREATE TABLE notification_settings (
    repository_id        uuid         PRIMARY KEY REFERENCES repositories(id) ON DELETE CASCADE,
    condition            varchar(16)  NOT NULL DEFAULT 'TRANSITION',
    slack_webhook_cipher text,
    slack_webhook_hint   varchar(16),
    email_recipients     jsonb        NOT NULL DEFAULT '[]'::jsonb,
    pull_request_comment boolean      NOT NULL DEFAULT false,
    updated_by           uuid         REFERENCES users(id) ON DELETE SET NULL,
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT notification_settings_condition_check CHECK (condition IN
        ('EVERY_RUN','TRANSITION','FAIL_ONLY','DISABLED'))
);

-- システム全体の設定（保持期間など）。キーごとに 1 行、値は JSON。
CREATE TABLE system_settings (
    key        varchar(64) PRIMARY KEY,
    value      jsonb       NOT NULL,
    updated_by uuid        REFERENCES users(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 通知の送信履歴は Run を持たない通知（免除の期限接近、計測途絶）も記録する。
-- run_id が NULL の行は一意制約が効かないため、再送の抑止に使う鍵を別に持つ。
ALTER TABLE notifications ADD COLUMN dedup_key varchar(255);
CREATE UNIQUE INDEX ux_notifications_dedup ON notifications (event, channel, target, dedup_key)
    WHERE dedup_key IS NOT NULL;
