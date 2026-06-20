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
        String query = "SELECT c.email, u.name AS user_name, l.amount " +
                "FROM loans l " +
                "INNER JOIN contacts c ON l.contact_id = c.id " +
                "INNER JOIN users u ON l.user_id = u.id " +
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
                String email = rs.getString("email");
                String userName = rs.getString("user_name");
                double amount = rs.getDouble("amount");

                // ইমেইলের সাবজেক্ট এবং মূল মেসেজ তৈরি করা হচ্ছে
                String subject = "Loan Due Reminder";
                String message = "Dear " + userName + ",\n\n" +
                        "Your loan of " + amount + " taka is due tomorrow. " +
                        "Please repay the loan on time.\n\nThank you.";

                // ইমেইল ঠিকানা খালি না থাকলে আমাদের তৈরি করা NotificationService এর মাধ্যমে
                // ইমেইল পাঠিয়ে দেওয়া হচ্ছে
                if (email != null && !email.trim().isEmpty()) {
                    NotificationService.sendEmail(email, subject, message);
                }
            }

        } catch (SQLException e) {
            System.out.println("Error in fetching data from the database: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
