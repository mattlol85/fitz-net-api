package org.fitznet.fitznetapi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailService {

  private final JavaMailSender mailSender;

  @Value("${mail.from}")
  private String from;

  @Value("${app.base-url}")
  private String appBaseUrl;

  public EmailService(JavaMailSender mailSender) {
    this.mailSender = mailSender;
  }

  public void sendPasswordReset(String toEmail, String resetToken) {
    String resetUrl = appBaseUrl + "/reset-password?token=" + resetToken;
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(toEmail);
    message.setSubject("Fitz-Net Password Reset");
    message.setText(
        "You requested a password reset for your Fitz-Net account.\n\n"
            + "Click the link below to reset your password (expires in 15 minutes):\n\n"
            + resetUrl
            + "\n\nIf you did not request this, you can safely ignore this email.");
    mailSender.send(message);
    log.info("Password reset email sent to {}", toEmail);
  }
}
