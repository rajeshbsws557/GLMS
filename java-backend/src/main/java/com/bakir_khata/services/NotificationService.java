package com.bakir_khata.services;

import javax.mail.*;
import javax.mail.internet.*;
import java.util.Properties;

public class NotificationService {

    private static final String SENDER_EMAIL = "gb03088844@gmail.com";
    private static final String APP_PASSWORD = "pigr iwwk ppcj vewj";

    public static void sendEmail(String recipientEmail, String subject, String messageBody) {

        Properties properties = new Properties();
        properties.put("mail.smtp.auth", "true"); 
        properties.put("mail.smtp.starttls.enable", "true"); 
        properties.put("mail.smtp.host", "smtp.gmail.com"); 
        properties.put("mail.smtp.port", "587"); 

        Session session = Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(SENDER_EMAIL, APP_PASSWORD);
            }
        });

        try {

            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(SENDER_EMAIL, "Bakir Khata Notifications")); 
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipientEmail)); 

            message.setSubject(subject); 
            message.setContent(messageBody, "text/html; charset=utf-8"); 

            Transport.send(message);
            System.out.println("Success in sending the mail: " + recipientEmail);

        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            System.out.println("Failed to send the mail: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
