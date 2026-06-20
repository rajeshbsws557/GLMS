package com.bakir_khata.services;

import com.bakir_khata.DatabaseHelper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DueLoanNotifier {

    public static void checkAndSendDueNotifications() {

        // ১. এসকিউএল (SQL) কোয়েরি এবং ইন্টারভ্যাল (INTERVAL) লজিক:
        // এখানে INNER JOIN ব্যবহার করে তিনটি ভিন্ন টেবিল (loans, contacts এবং users)
        // একসাথে যুক্ত করা হয়েছে।
        // "status != 'Settled'" দিয়ে আমরা নিশ্চিত করছি যে লোনটি এখনও শোধ করা হয়নি।
        // "due_date = CURDATE() + INTERVAL 1 DAY" অংশটি অত্যন্ত গুরুত্বপূর্ণ। এটি
        // বর্তমান তারিখের সাথে ঠিক ১ দিন যোগ করে
        // আগামীকালের তারিখ বের করে। এর ফলে কোয়েরিটি শুধু সেই লোনগুলোই খুঁজে বের করবে
        // যেগুলোর পরিশোধের শেষ দিন আগামীকাল।
        String query = "SELECT u.email AS user_email, u.full_name AS user_name, " +
                "c.email AS contact_email, c.contact_name, l.amount, l.loan_type " +
                "FROM loans l " +
                "INNER JOIN contacts c ON l.contact_id = c.contact_id " +
                "INNER JOIN users u ON l.user_id = u.user_id " +
                "WHERE l.status != 'Settled' " +
                "AND l.due_date = CURDATE() + INTERVAL 1 DAY";

        // ডেটাবেস কানেকশন তৈরি করা হচ্ছে DatabaseHelper ক্লাস ব্যবহার করে
        try (Connection conn = DatabaseHelper.getInstance().getConnection();
                PreparedStatement stmt = conn.prepareStatement(query);
                ResultSet rs = stmt.executeQuery()) {

            // ২. রেজাল্টসেট (ResultSet) থেকে ডাটা বের করা এবং ইমেইল পাঠানো:
            // rs.next() মেথডটি লুপের সাহায্যে ডেটাবেস থেকে পাওয়া প্রতিটি সারির (row) উপর
            // দিয়ে ধাপে ধাপে যাবে।
            // প্রতিটি সারি থেকে আমরা ইমেইল, ইউজারের নাম এবং লোনের পরিমাণ সংগ্রহ করে
            // নোটিফিকেশন সার্ভিসের কাছে পাঠাবো।
            while (rs.next()) {
                String userEmail = rs.getString("user_email");
                String userName = rs.getString("user_name");
                String contactEmail = rs.getString("contact_email");
                String contactName = rs.getString("contact_name");
                double amount = rs.getDouble("amount");
                String loanType = rs.getString("loan_type");

                String subject = "Loan Due Reminder";
                String recipientEmail = null;
                String message = "";

                if ("Lent".equalsIgnoreCase(loanType)) {
                    // User lent money to the contact. So the contact should repay the user.
                    // Notify the contact.
                    recipientEmail = contactEmail;
                    message = "<html><body style='font-family: sans-serif; color: #333;'>" +
                            "<div style='max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #eaeaea; border-radius: 8px;'>" +
                            "<h2 style='color: #2563eb;'>Bakir Khata Reminder</h2>" +
                            "<p>Dear <strong>" + contactName + "</strong>,</p>" +
                            "<p>This is a friendly reminder that your loan of <strong>" + amount + " taka</strong> from <strong>" + userName + "</strong> is due tomorrow.</p>" +
                            "<p>Please ensure you repay the loan on time.</p>" +
                            "<br><p>Thank you,<br>Bakir Khata Team</p>" +
                            "<hr style='border: none; border-top: 1px solid #eaeaea; margin-top: 20px;'>" +
                            "<p style='font-size: 12px; color: #888;'>You are receiving this automated notification because you are registered as a contact in Bakir Khata.</p>" +
                            "</div></body></html>";
                } else if ("Borrowed".equalsIgnoreCase(loanType)) {
                    // User borrowed money from the contact. The user should repay the contact.
                    // Notify the user.
                    recipientEmail = userEmail;
                    message = "<html><body style='font-family: sans-serif; color: #333;'>" +
                            "<div style='max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #eaeaea; border-radius: 8px;'>" +
                            "<h2 style='color: #2563eb;'>Bakir Khata Reminder</h2>" +
                            "<p>Dear <strong>" + userName + "</strong>,</p>" +
                            "<p>This is a friendly reminder that your loan of <strong>" + amount + " taka</strong> to <strong>" + contactName + "</strong> is due tomorrow.</p>" +
                            "<p>Please ensure you repay the loan on time.</p>" +
                            "<br><p>Thank you,<br>Bakir Khata Team</p>" +
                            "<hr style='border: none; border-top: 1px solid #eaeaea; margin-top: 20px;'>" +
                            "<p style='font-size: 12px; color: #888;'>You are receiving this automated notification because you use Bakir Khata.</p>" +
                            "</div></body></html>";
                }

                // ইমেইল ঠিকানা খালি না থাকলে আমাদের তৈরি করা NotificationService এর মাধ্যমে
                // ইমেইল পাঠিয়ে দেওয়া হচ্ছে
                if (recipientEmail != null && !recipientEmail.trim().isEmpty()) {
                    NotificationService.sendEmail(recipientEmail, subject, message);
                }
            }

        } catch (SQLException e) {
            System.out.println("Error in fetching data from the database: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
