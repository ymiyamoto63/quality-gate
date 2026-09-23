package com.qualitygate.notify;

/**
 * メール通知の件名と本文（プレーンテキスト）。
 *
 * @param subject 件名
 * @param text    本文
 */
public record NotificationMessage(String subject, String text) {
}
