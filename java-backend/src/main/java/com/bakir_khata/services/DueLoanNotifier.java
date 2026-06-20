package com.bakir_khata.services;

import com.bakir_khata.DatabaseHelper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DueLoanNotifier {

    public static void checkAndSendDueNotifications() {

        String query = "SELECT u.email AS user_email, u.full_name AS user_name, " +
                "c.email AS contact_email, c.contact_name, l.amount, l.loan_type " +
                "FROM loans l " +
                "INNER JOIN contacts c ON l.contact_id = c.contact_id " +
                "INNER JOIN users u ON l.user_id = u.user_id " +
                "WHERE l.status != 'Settled' " +
                "AND l.due_date = CURDATE() + INTERVAL 1 DAY";

        try (Connection conn = DatabaseHelper.getInstance().getConnection();
                PreparedStatement stmt = conn.prepareStatement(query);
                ResultSet rs = stmt.executeQuery()) {

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
