package com.bakir_khata.services;

import javax.mail.*;
import javax.mail.internet.*;
import java.util.Properties;

public class NotificationService {

    // প্রেরকের ইমেইল ঠিকানা এবং অ্যাপ পাসওয়ার্ড সংরক্ষণের জন্য ভেরিয়েবল
    private static final String SENDER_EMAIL = "your_email@gmail.com";
    private static final String APP_PASSWORD = "your_app_password_here";

    public static void sendEmail(String recipientEmail, String subject, String messageBody) {

        // ১. এসএমটিপি (SMTP) প্রপার্টি কনফিগারেশন:
        // এখানে আমরা জিমেইলের সার্ভারের ঠিকানা, পোর্ট এবং সিকিউরিটি (TLS) প্রপার্টি সেট
        // করছি।
        // টিএলএস (TLS) মূলত আমাদের ইমেইল ডেটাকে এনক্রিপ্ট করে ইন্টারনেটে সুরক্ষিতভাবে
        // পাঠাতে সাহায্য করে।
        Properties properties = new Properties();
        properties.put("mail.smtp.auth", "true"); // সার্ভারে লগইন করার জন্য অথেনটিকেশন বাধ্যতামূলক করছি
        properties.put("mail.smtp.starttls.enable", "true"); // TLS এনক্রিপশন চালু করছি
        properties.put("mail.smtp.host", "smtp.gmail.com"); // জিমেইলের নিজস্ব SMTP সার্ভারের ঠিকানা
        properties.put("mail.smtp.port", "587"); // TLS এনক্রিপশনের জন্য জিমেইলের পোর্ট নম্বর

        // ২. সেশন (Session) এবং অথেনটিকেটর (Authenticator) তৈরি:
        // Authenticator একটি বিশেষ ক্লাস যা সার্ভারকে আমাদের ইমেইল এবং অ্যাপ পাসওয়ার্ড
        // দিয়ে যাচাই করে।
        // জিমেইলে সাধারণ পাসওয়ার্ডের বদলে আলাদা 'অ্যাপ পাসওয়ার্ড' ব্যবহার করা অনেক
        // বেশি নিরাপদ।
        Session session = Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(SENDER_EMAIL, APP_PASSWORD);
            }
        });

        try {
            // ৩. মিম মেসেজ (MimeMessage) ড্রাফট তৈরি:
            // MimeMessage ক্লাসের মাধ্যমে আমরা একটি ইমেইল এর সম্পূর্ণ গঠন (যেমন- প্রাপক,
            // বিষয়বস্তু, মেসেজ বডি) তৈরি করি।
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(SENDER_EMAIL)); // প্রেরকের ঠিকানা নির্ধারণ করা
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipientEmail)); // প্রাপকের ঠিকানা
                                                                                                 // নির্ধারণ করা
            message.setSubject(subject); // ইমেইলের মূল বিষয় বা সাবজেক্ট
            message.setText(messageBody); // ইমেইলের ভেতরে থাকা আসল লেখা বা বডি

            // ৪. ইমেইল পাঠানো:
            // Transport.send() মেথডটি পূর্বে তৈরি করা সেশন এবং কনফিগারেশন ব্যবহার করে
            // মেসেজটি প্রাপকের কাছে পাঠিয়ে দেয়।
            Transport.send(message);
            System.out.println("Success in sending the mail: " + recipientEmail);

        } catch (MessagingException e) {
            System.out.println("Failed to send the mail: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
