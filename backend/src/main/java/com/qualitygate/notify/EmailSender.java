package com.qualitygate.notify;

import com.qualitygate.platform.config.QualityGateProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** SMTP リレーでメールを送る。{@code spring.mail.host} が空なら送らない。 */
@Component
public class EmailSender {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final Environment environment;
    private final QualityGateProperties properties;

    public EmailSender(ObjectProvider<JavaMailSender> mailSender, Environment environment,
                       QualityGateProperties properties) {
        this.mailSender = mailSender;
        this.environment = environment;
        this.properties = properties;
    }

    public boolean isConfigured() {
        String host = environment.getProperty("spring.mail.host");
        return host != null && !host.isBlank() && mailSender.getIfAvailable() != null;
    }

    public ChannelResult send(String to, NotificationMessage message) {
        if (!isConfigured()) {
            return ChannelResult.failed("SMTP が設定されていません（QG_SMTP_HOST）", false);
        }
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(properties.notification().mailFrom());
        mail.setTo(to);
        mail.setSubject(message.subject());
        mail.setText(message.text());
        try {
            mailSender.getObject().send(mail);
            return ChannelResult.ok();
        } catch (MailException e) {
            return ChannelResult.failed("メールを送信できませんでした: " + e.getMessage(), true);
        }
    }
}
