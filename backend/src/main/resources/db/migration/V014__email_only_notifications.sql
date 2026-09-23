-- 通知チャネルをメールのみにする。
--
-- V012 では Slack（Incoming Webhook）と PR コメントも通知先として用意したが、
-- 運用上の判断により通知はメールだけで足りることになった。使わない列を残すと、
-- 設定したつもりで何も届かない項目が画面と API に残るため削除する。
ALTER TABLE notification_settings DROP COLUMN slack_webhook_cipher;
ALTER TABLE notification_settings DROP COLUMN slack_webhook_hint;
ALTER TABLE notification_settings DROP COLUMN pull_request_comment;
