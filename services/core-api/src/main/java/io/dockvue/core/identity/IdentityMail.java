package io.dockvue.core.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
class IdentityMail {
    private static final Logger LOG=LoggerFactory.getLogger(IdentityMail.class);
    private final JavaMailSender mail;
    private final String from;
    IdentityMail(JavaMailSender mail, @Value("${dockvue.auth.mail-from:no-reply@dockvue.local}") String from,
        @Value("${dockvue.auth.dev:false}") boolean dev,
        @Value("${spring.mail.properties.mail.smtp.starttls.required:false}") boolean tls) {
        if(!dev&&!tls)throw new IllegalStateException("Production SMTP requires TLS");
        this.mail=mail;this.from=from;
    }
    void afterCommit(String to, String purpose, String token) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    SimpleMailMessage m=new SimpleMailMessage();
                    m.setFrom(from); m.setTo(to); m.setSubject("DockVue "+purpose);
                    m.setText("Enter this one-time code in DockVue. Do not share it.\n\n"+token+"\n");
                    mail.send(m);
                } catch (RuntimeException e) {
                    // Never log recipient, token, or exception text (SMTP may echo them).
                    LOG.warn("Identity email delivery failed; request a new code");
                }
            }
        });
    }
}
