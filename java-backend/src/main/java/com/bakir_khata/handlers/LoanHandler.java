package com.bakir_khata.handlers;

/*
 * ============================================================================
 * ফাইল: LoanHandler.java — ঋণ ব্যবস্থাপনা (Loan Management)
 * ============================================================================
 *
 * উদ্দেশ্য:
 * এই ক্লাসটি PHP-র loans.php ফাইলের সম্পূর্ণ Java রূপান্তর।
 * অ্যাপ্লিকেশনের মূল (core) টেবিলের CRUD পরিচালনা করে।
 *
 * দুটি HTTP method সমর্থন করে:
 *   GET  /api/loans?user_id=1 → ঋণ তালিকা দেখা (INNER JOIN + subquery সহ)
 *   POST /api/loans           → নতুন ঋণ তৈরি
 *
 * ============================================================================
 * গুরুত্বপূর্ণ DBMS ধারণা (ভাইভায় জিজ্ঞেস করা হবে!):
 * ============================================================================
 *
 * ১. INNER JOIN:
 *    দুটি টেবিলের (loans, contacts) সম্পর্কিত row গুলো মিলিয়ে দেয়।
 *    loans.contact_id = contacts.contact_id → কোন contact-এর সাথে ঋণ
 *    শুধু যেসব row-তে MATCH পাওয়া যায়, সেগুলোই ফলাফলে আসে।
 *
 * ২. Correlated Subquery (সম্পর্কযুক্ত উপ-কোয়েরি):
 *    SELECT SUM(r.amount_paid) FROM repayments r WHERE r.loan_id = l.loan_id
 *    → প্রতিটি ঋণের জন্য মোট পরিশোধিত অর্থ হিসাব করে
 *    "correlated" কারণ এটি বাইরের query-র l.loan_id ব্যবহার করে
 *
 * ৩. COALESCE(value, default):
 *    SUM() কোনো row না পেলে NULL ফেরত দেয়, 0 নয়!
 *    COALESCE NULL-কে 0 দিয়ে প্রতিস্থাপন করে।
 *
 * ৪. CASE Expression (Custom Sort Order):
 *    ORDER BY CASE status WHEN 'Unpaid' THEN 1 ... END
 *    → নিজস্ব ক্রম সংজ্ঞায়িত করে (Unpaid আগে, Settled শেষে)
 *
 * ৫. ENUM Type:
 *    loan_type ENUM('Lent', 'Borrowed') → শুধু এই দুটি মান গ্রহণযোগ্য
 *    ডাটাবেস স্তরে validation — অ্যাপ্লিকেশন কোড ভুল মান পাঠালেও
 *    ডাটাবেস প্রত্যাখ্যান করবে।
 * ============================================================================
 */

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;

import com.bakir_khata.DatabaseHelper;

/**
 * LoanHandler — /api/loans endpoint-এর handler।
 *
 * OOP ধারণা → Single Responsibility Principle (SRP):
 * ─────────────────────────────────────────────────────
 * প্রতিটি Handler ক্লাসের শুধু একটি দায়িত্ব — নির্দিষ্ট resource
 * পরিচালনা করা। LoanHandler শুধু loans-এর কাজ করে,
 * contacts বা repayments-এর কাজ আলাদা Handler-এ।
 * এটি SOLID নীতিমালার প্রথম নীতি (S = Single Responsibility)।
 */
public class LoanHandler implements HttpHandler {

    private final Gson gson = new Gson();

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        if (CorsUtil.handleCors(exchange)) return;

        String method = exchange.getRequestMethod().toUpperCase();

        switch (method) {
            case "GET":
                getLoans(exchange);
                break;
            case "POST":
                addLoan(exchange);
                break;
            default:
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Method not allowed. Use GET or POST.");
                CorsUtil.sendJsonResponse(exchange, 405, gson.toJson(error));
                break;
        }
    }


    // ========================================================================
    // getLoans() — ঋণ তালিকা দেখানো (সবচেয়ে জটিল SQL Query!)
    // ========================================================================
    /**
     * GET /api/loans?user_id=1 → user 1-এর সব ঋণ ফেরত দেয়
     *
     * এটি পুরো অ্যাপ্লিকেশনের সবচেয়ে জটিল SQL query ধারণ করে।
     * ভাইভায় এই query সম্পর্কে জিজ্ঞেস হওয়ার সম্ভাবনা সবচেয়ে বেশি!
     *
     * SQL Query বিশ্লেষণ (ধাপে ধাপে):
     * ─────────────────────────────────────────────────────────────────
     *
     * SELECT
     *     l.loan_id, l.user_id, l.contact_id, l.loan_type,
     *     l.amount, l.loan_date, l.due_date, l.status, l.notes,
     *     c.contact_name,                              ← JOIN থেকে আসে
     *     COALESCE(
     *         (SELECT SUM(r.amount_paid)               ← Correlated Subquery
     *          FROM repayments r
     *          WHERE r.loan_id = l.loan_id), 0
     *     ) AS total_paid                              ← Alias (উপনাম)
     * FROM loans l
     *     INNER JOIN contacts c                        ← দুই টেবিল মেলানো
     *     ON l.contact_id = c.contact_id
     * WHERE l.user_id = ?                              ← ফিল্টার
     * ORDER BY
     *     CASE l.status                                ← Custom Sort
     *         WHEN 'Unpaid' THEN 1
     *         WHEN 'Partially Paid' THEN 2
     *         WHEN 'Settled' THEN 3
     *     END,
     *     l.loan_date DESC                             ← তারিখ অনুযায়ী
     *
     * ─────────────────────────────────────────────────────────────────
     *
     * INNER JOIN কীভাবে কাজ করে?
     *   loans টেবিল:    contact_id = 5
     *   contacts টেবিল: contact_id = 5, contact_name = "রহিম"
     *   → ফলাফলে দুই টেবিলের column একত্রিত হয়
     *   → contact_id=5 না থাকলে সেই loan ফলাফলে আসবে না
     *
     * Correlated Subquery কীভাবে কাজ করে?
     *   প্রতিটি loan row-র জন্য আলাদাভাবে চলে:
     *   loan_id=1 → SELECT SUM(...) WHERE loan_id = 1 → 500
     *   loan_id=2 → SELECT SUM(...) WHERE loan_id = 2 → 0
     *   loan_id=3 → SELECT SUM(...) WHERE loan_id = 3 → 300
     *
     * @param exchange HttpExchange object
     * @throws IOException response পাঠাতে ব্যর্থ হলে
     */
    private void getLoans(HttpExchange exchange) throws IOException {

        Map<String, String> params = parseQueryParams(exchange);

        if (!params.containsKey("user_id")) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Missing required parameter: user_id");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        int userId;
        try {
            userId = Integer.parseInt(params.get("user_id"));
        } catch (NumberFormatException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "user_id must be a valid integer.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();

            // ====================================================================
            // জটিল SQL Query — INNER JOIN + Correlated Subquery + Custom Sort
            // ====================================================================
            // Java-তে দীর্ঘ SQL লেখার সময় String concatenation (+) ব্যবহার করি।
            // Java 17-এ Text Block (""" ... """) ব্যবহার করা যেত, কিন্তু
            // + concatenation শিক্ষার্থীদের জন্য বেশি পরিচিত।
            //
            // Table Alias:
            //   l → loans টেবিল
            //   c → contacts টেবিল
            //   r → repayments টেবিল (subquery-তে)
            //
            // প্রতিটি alias ব্যবহার করে column-এর পূর্ণ নাম (l.loan_id) লিখি
            // যাতে MySQL বুঝতে পারে কোন টেবিলের কোন column।
            // ====================================================================
            String sql = "SELECT " +
                    "l.loan_id, l.user_id, l.contact_id, l.loan_type, " +
                    "l.amount, l.loan_date, l.due_date, l.status, l.notes, " +
                    "c.contact_name, " +
                    "COALESCE(" +
                    "    (SELECT SUM(r.amount_paid) FROM repayments r WHERE r.loan_id = l.loan_id), 0" +
                    ") AS total_paid " +
                    "FROM loans l " +
                    "INNER JOIN contacts c ON l.contact_id = c.contact_id " +
                    "WHERE l.user_id = ? " +
                    "ORDER BY " +
                    "CASE l.status " +
                    "    WHEN 'Unpaid' THEN 1 " +
                    "    WHEN 'Partially Paid' THEN 2 " +
                    "    WHEN 'Settled' THEN 3 " +
                    "END, " +
                    "l.loan_date DESC";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);

                try (ResultSet rs = ps.executeQuery()) {
                    // ================================================================
                    // ResultSet → JSON Array রূপান্তর
                    // ================================================================
                    // PHP-তে fetchAll() সব row দেয় এবং foreach দিয়ে type cast করা হয়:
                    //   foreach ($loans as &$loan) {
                    //       $loan['amount'] = (float) $loan['amount'];
                    //   }
                    //
                    // Java-তে while loop দিয়ে প্রতিটি row পড়ি এবং সঠিক type
                    // ব্যবহার করি (getInt, getDouble, getString)।
                    //
                    // getDouble() → DECIMAL column-এর জন্য — PHP-র (float) cast-এর সমতুল্য
                    // getString() → TEXT/VARCHAR column-এর জন্য
                    // getInt() → INT column-এর জন্য
                    // ================================================================
                    JsonArray loansArray = new JsonArray();

                    while (rs.next()) {
                        JsonObject loan = new JsonObject();

                        // Integer ফিল্ড — PHP: (int) $loan['loan_id']
                        loan.addProperty("loan_id", rs.getInt("loan_id"));
                        loan.addProperty("user_id", rs.getInt("user_id"));
                        loan.addProperty("contact_id", rs.getInt("contact_id"));

                        // String ফিল্ড
                        loan.addProperty("loan_type", rs.getString("loan_type"));
                        loan.addProperty("status", rs.getString("status"));
                        loan.addProperty("contact_name", rs.getString("contact_name"));

                        // Decimal/Float ফিল্ড — PHP: (float) $loan['amount']
                        loan.addProperty("amount", rs.getDouble("amount"));
                        loan.addProperty("total_paid", rs.getDouble("total_paid"));

                        // Date ফিল্ড — getString() ব্যবহার করি যাতে
                        // "2024-01-15" ফরম্যাটে সরাসরি JSON-এ যায়
                        loan.addProperty("loan_date", rs.getString("loan_date"));

                        // Nullable ফিল্ড (due_date, notes)
                        String dueDate = rs.getString("due_date");
                        if (dueDate != null) {
                            loan.addProperty("due_date", dueDate);
                        } else {
                            loan.add("due_date", null);
                        }

                        String notes = rs.getString("notes");
                        if (notes != null) {
                            loan.addProperty("notes", notes);
                        } else {
                            loan.add("notes", null);
                        }

                        loansArray.add(loan);
                    }

                    // সফল response
                    JsonObject response = new JsonObject();
                    response.addProperty("success", true);
                    response.add("loans", loansArray);

                    CorsUtil.sendJsonResponse(exchange, 200, gson.toJson(response));
                }
            }

        } catch (SQLException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Failed to fetch loans: " + e.getMessage());
            CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
        }
    }


    // ========================================================================
    // addLoan() — নতুন ঋণ তৈরি করা
    // ========================================================================
    /**
     * POST /api/loans → নতুন ঋণ রেকর্ড তৈরি করে
     *
     * প্রত্যাশিত JSON Body:
     * {
     *   "user_id": 1,
     *   "contact_id": 3,
     *   "loan_type": "Lent",           // "Lent" বা "Borrowed"
     *   "amount": 500.00,
     *   "loan_date": "2024-01-15",
     *   "due_date": "2024-03-15",       // ঐচ্ছিক
     *   "notes": "বইয়ের জন্য"           // ঐচ্ছিক
     * }
     *
     * Validation (PHP-র হুবহু অনুরূপ):
     *   - user_id, contact_id, loan_type, amount, loan_date → বাধ্যতামূলক
     *   - loan_type অবশ্যই 'Lent' বা 'Borrowed' হতে হবে
     *   - amount অবশ্যই ধনাত্মক সংখ্যা হতে হবে
     *   - status ডাটাবেসের DEFAULT ('Unpaid') ব্যবহার করবে
     *
     * @param exchange HttpExchange object
     * @throws IOException response পাঠাতে ব্যর্থ হলে
     */
    private void addLoan(HttpExchange exchange) throws IOException {

        String requestBody = readRequestBody(exchange);
        JsonObject data;

        try {
            data = JsonParser.parseString(requestBody).getAsJsonObject();
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Invalid JSON in request body.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        // ====================================================================
        // বাধ্যতামূলক ফিল্ড যাচাই
        // ====================================================================
        // PHP-তে: foreach ($required_fields as $field) { if (!isset($data[$field])) { ... } }
        //
        // Java-তে String array দিয়ে loop চালাই এবং প্রতিটি ফিল্ড চেক করি।
        // ====================================================================
        String[] requiredFields = {"user_id", "contact_id", "loan_type", "amount", "loan_date"};

        for (String field : requiredFields) {
            if (!data.has(field) || data.get(field).isJsonNull()) {
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Missing required field: " + field);
                CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
                return;
            }
            // String ফিল্ড ফাঁকা কিনা চেক
            if (data.get(field).isJsonPrimitive() && data.get(field).getAsJsonPrimitive().isString()) {
                if (data.get(field).getAsString().trim().isEmpty()) {
                    JsonObject error = new JsonObject();
                    error.addProperty("success", false);
                    error.addProperty("message", "Missing required field: " + field);
                    CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
                    return;
                }
            }
        }

        // loan_type validation — ENUM মান যাচাই
        String loanType = data.get("loan_type").getAsString();
        if (!"Lent".equals(loanType) && !"Borrowed".equals(loanType)) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "loan_type must be either \"Lent\" or \"Borrowed\".");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        // amount validation — ধনাত্মক সংখ্যা চেক
        double amount = data.get("amount").getAsDouble();
        if (amount <= 0) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Amount must be a positive number.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        // ফিল্ড বের করা (extract)
        int userId = data.get("user_id").getAsInt();
        int contactId = data.get("contact_id").getAsInt();
        String loanDate = data.get("loan_date").getAsString();

        // ঐচ্ছিক ফিল্ড — না থাকলে null
        String dueDate = null;
        if (data.has("due_date") && !data.get("due_date").isJsonNull()) {
            String dd = data.get("due_date").getAsString().trim();
            if (!dd.isEmpty()) dueDate = dd;
        }

        String notes = null;
        if (data.has("notes") && !data.get("notes").isJsonNull()) {
            notes = data.get("notes").getAsString().trim();
            if (notes.isEmpty()) notes = null;
        }

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();

            // ====================================================================
            // INSERT query — নতুন ঋণ ঢোকানো
            // ====================================================================
            // status column ইচ্ছাকৃতভাবে INSERT-এ দেওয়া হচ্ছে না।
            // কেন? ডাটাবেসের DEFAULT 'Unpaid' স্বয়ংক্রিয়ভাবে ব্যবহৃত হবে।
            //
            // এটি ভালো অভ্যাস — default মান একটি জায়গায় (schema) সংজ্ঞায়িত
            // করলে ভবিষ্যতে পরিবর্তন সহজ হয়। অ্যাপ্লিকেশন কোডে hardcode
            // করলে দুই জায়গায় পরিবর্তন করতে হবে।
            // ====================================================================
            String sql = "INSERT INTO loans (user_id, contact_id, loan_type, amount, loan_date, due_date, notes) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement ps = conn.prepareStatement(sql,
                    PreparedStatement.RETURN_GENERATED_KEYS)) {

                ps.setInt(1, userId);
                ps.setInt(2, contactId);
                ps.setString(3, loanType);
                ps.setDouble(4, amount);
                ps.setString(5, loanDate);

                // Nullable ফিল্ড হ্যান্ডেল
                if (dueDate != null) {
                    ps.setString(6, dueDate);
                } else {
                    ps.setNull(6, java.sql.Types.DATE);
                }

                if (notes != null) {
                    ps.setString(7, notes);
                } else {
                    ps.setNull(7, java.sql.Types.VARCHAR);
                }

                ps.executeUpdate();

                ResultSet generatedKeys = ps.getGeneratedKeys();
                int newLoanId = 0;
                if (generatedKeys.next()) {
                    newLoanId = generatedKeys.getInt(1);
                }

                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("message", "Loan created successfully!");
                response.addProperty("loan_id", newLoanId);

                CorsUtil.sendJsonResponse(exchange, 201, gson.toJson(response));
            }

        } catch (SQLException e) {
            // Foreign Key violation → অবৈধ user_id বা contact_id
            if ("23000".equals(e.getSQLState())) {
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Invalid user_id or contact_id. Referenced records must exist.");
                CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            } else {
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Failed to create loan: " + e.getMessage());
                CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
            }
        }
    }


    // ========================================================================
    // সহায়ক Methods (Helper Methods)
    // ========================================================================

    /**
     * URL query string parse করে key-value Map ফেরত দেয়।
     * ContactHandler-এ বিস্তারিত ব্যাখ্যা আছে।
     */
    private Map<String, String> parseQueryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getQuery();
        if (query != null && !query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) params.put(kv[0], kv[1]);
                else if (kv.length == 1) params.put(kv[0], "");
            }
        }
        return params;
    }

    /** Request body পড়ে String হিসেবে ফেরত দেয়। */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }
}
