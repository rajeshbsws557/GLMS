package com.bakir_khata.handlers;

/*
 * ============================================================================
 * ফাইল: RepaymentHandler.java — পরিশোধ ব্যবস্থাপনা (ACID Transaction সহ)
 * ============================================================================
 *
 * ⚠️ এটি পুরো ব্যাকএন্ডের সবচেয়ে গুরুত্বপূর্ণ ফাইল! ⚠️
 * ভাইভায় এই ফাইলের Transaction লজিক সম্পর্কে প্রশ্ন হবেই!
 *
 * উদ্দেশ্য:
 * এই ক্লাসটি PHP-র repayments.php ফাইলের সম্পূর্ণ Java রূপান্তর।
 * ঋণের বিপরীতে পরিশোধ (repayment) রেকর্ড করে এবং ঋণের স্ট্যাটাস
 * স্বয়ংক্রিয়ভাবে আপডেট করে।
 *
 * দুটি HTTP method সমর্থন করে:
 *   POST /api/repayments        → নতুন পরিশোধ রেকর্ড (Transaction সহ)
 *   GET  /api/repayments?loan_id=5 → নির্দিষ্ট ঋণের পরিশোধ ইতিহাস
 *
 * ============================================================================
 * 🔴 ভাইভার সবচেয়ে গুরুত্বপূর্ণ ধারণা: DATABASE TRANSACTION 🔴
 * ============================================================================
 *
 * Transaction কী?
 * ─────────────────
 * Transaction হলো একগুচ্ছ SQL statement যা একটি অবিভাজ্য (atomic) একক
 * হিসেবে কাজ করে। হয় সবগুলো সফল হবে, নয়তো একটিও কার্যকর হবে না।
 *
 * বাস্তব উদাহরণ (ব্যাংক ট্রান্সফার):
 *   ধাপ ১: রহিমের অ্যাকাউন্ট থেকে ৫০০ টাকা কমাও    ← সফল ✓
 *   ধাপ ২: করিমের অ্যাকাউন্টে ৫০০ টাকা যোগ করো     ← সার্ভার crash! ✗
 *
 *   Transaction ছাড়া: রহিমের ৫০০ টাকা গায়েব, করিম পায়নি! 💸
 *   Transaction সহ: সবকিছু rollback — রহিমের ৫০০ টাকা ফিরে আসে। ✅
 *
 * আমাদের অ্যাপ্লিকেশনে Transaction:
 *   ধাপ ১: repayments table-এ INSERT        ← পরিশোধ রেকর্ড করো
 *   ধাপ ২: SUM(amount_paid) গণনা করো        ← মোট পরিশোধ বের করো
 *   ধাপ ৩: loans table-এ status UPDATE করো  ← স্ট্যাটাস আপডেট করো
 *
 *   যদি ধাপ ৩ ব্যর্থ হয় → ধাপ ১-এর INSERT UNDO হয়ে যাবে!
 *
 * ============================================================================
 * ACID Properties (ভাইভায় ব্যাখ্যা করতে হবে!):
 * ============================================================================
 *
 *   A — Atomicity (অবিভাজ্যতা):
 *       "সব অথবা কিছুই নয়।"
 *       Transaction-এর সব statement হয় সফল হবে (COMMIT),
 *       নয়তো সব বাতিল হবে (ROLLBACK)।
 *       Java-তে: conn.commit() বা conn.rollback()
 *
 *   C — Consistency (সামঞ্জস্যতা):
 *       "ডাটাবেস সবসময় বৈধ অবস্থায় থাকবে।"
 *       Transaction-এর আগে ও পরে সব constraint (FOREIGN KEY, UNIQUE,
 *       CHECK) মেনে চলবে। মাঝপথে ডেটা inconsistent হলেও commit-এ
 *       ডাটাবেস consistent অবস্থায় থাকবে।
 *
 *   I — Isolation (বিচ্ছিন্নতা):
 *       "একসাথে চলা transaction গুলো পরস্পরকে প্রভাবিত করবে না।"
 *       দুজন একই সময়ে পরিশোধ করলে, একজনের transaction শেষ না হওয়া
 *       পর্যন্ত অন্যজন পুরনো ডেটা দেখবে না।
 *
 *   D — Durability (স্থায়িত্ব):
 *       "COMMIT-এর পর ডেটা স্থায়ী।"
 *       সার্ভার crash হলেও committed ডেটা হারাবে না — ডিস্কে লেখা হয়ে
 *       গেছে (InnoDB-র Write-Ahead Log এটি নিশ্চিত করে)।
 *
 * ============================================================================
 * JDBC-তে Transaction পরিচালনা (PHP-র সাথে তুলনা):
 * ============================================================================
 *
 *   PHP:                              Java:
 *   $pdo->beginTransaction()     →    conn.setAutoCommit(false)
 *   $pdo->commit()               →    conn.commit()
 *   $pdo->rollBack()             →    conn.rollback()
 *
 *   পার্থক্য:
 *   - PHP-তে beginTransaction() কল করি
 *   - Java-তে setAutoCommit(false) কল করি
 *     "Auto Commit" মানে প্রতিটি SQL statement আলাদাভাবে commit হয়।
 *     false করলে আমরা ম্যানুয়ালি commit/rollback নিয়ন্ত্রণ করি।
 *
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
 * RepaymentHandler — /api/repayments endpoint-এর handler।
 *
 * এই ক্লাসে JDBC Transaction ব্যবহৃত হয়েছে — ভাইভার মূল বিষয়।
 *
 * OOP ধারণা → Exception Handling (ত্রুটি ব্যবস্থাপনা):
 * ─────────────────────────────────────────────────────
 * try-catch-finally → Java-র ত্রুটি ব্যবস্থাপনার মূল কাঠামো।
 *   try → ত্রুটি হতে পারে এমন কোড
 *   catch → ত্রুটি ধরা ও সামলানো
 *   finally → ত্রুটি হোক বা না হোক, সবসময় চলবে (cleanup)
 *
 * Transaction-এ এটি অত্যন্ত গুরুত্বপূর্ণ:
 *   try { ... commit(); }
 *   catch { rollback(); }    ← ত্রুটি হলে সব বাতিল
 *   finally { setAutoCommit(true); }  ← কানেকশন আগের অবস্থায় ফেরত
 */
public class RepaymentHandler implements HttpHandler {

    private final Gson gson = new Gson();

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        if (CorsUtil.handleCors(exchange)) return;

        String method = exchange.getRequestMethod().toUpperCase();

        switch (method) {
            case "POST":
                addRepayment(exchange);
                break;
            case "GET":
                getRepayments(exchange);
                break;
            default:
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Method not allowed. Use POST to add a repayment or GET to view repayments.");
                CorsUtil.sendJsonResponse(exchange, 405, gson.toJson(error));
                break;
        }
    }


    // ========================================================================
    // addRepayment() — 🔴 নতুন পরিশোধ রেকর্ড (ACID TRANSACTION) 🔴
    // ========================================================================
    /**
     * POST /api/repayments → পরিশোধ রেকর্ড করে ও ঋণের স্ট্যাটাস আপডেট করে
     *
     * প্রত্যাশিত JSON Body:
     * {
     *   "loan_id": 5,
     *   "amount_paid": 200.00
     * }
     *
     * ============================================================================
     * Transaction-ভিত্তিক কর্মপ্রবাহ (Workflow):
     * ============================================================================
     *
     * ┌────────────────────────────────────────────────────────────────────────┐
     * │  conn.setAutoCommit(false)  ← Transaction শুরু                        │
     * │                                                                        │
     * │  ধাপ ১: ঋণ যাচাই (SELECT ... FOR UPDATE)                              │
     * │         → ঋণটি আছে কিনা এবং ইতিমধ্যে Settled কিনা                    │
     * │         → FOR UPDATE → row-তে lock লাগায় (race condition প্রতিরোধ)    │
     * │                                                                        │
     * │  ধাপ ২: পরিশোধ INSERT করা                                              │
     * │         → repayments table-এ নতুন row                                 │
     * │                                                                        │
     * │  ধাপ ৩: মোট পরিশোধ গণনা (SELECT SUM)                                  │
     * │         → এই ঋণের সব repayment-এর যোগফল                              │
     * │                                                                        │
     * │  ধাপ ৪: নতুন স্ট্যাটাস নির্ধারণ                                       │
     * │         → total_paid >= amount → 'Settled'                             │
     * │         → total_paid > 0       → 'Partially Paid'                     │
     * │         → otherwise            → 'Unpaid'                              │
     * │                                                                        │
     * │  ধাপ ৫: ঋণের স্ট্যাটাস UPDATE করা                                     │
     * │         → loans table-এ status কলাম পরিবর্তন                          │
     * │                                                                        │
     * │  conn.commit()          ← সব সফল → স্থায়ী করো                         │
     * │  ── অথবা ──                                                            │
     * │  conn.rollback()        ← কোনো ত্রুটি → সব বাতিল করো                  │
     * │                                                                        │
     * │  conn.setAutoCommit(true)  ← কানেকশন আগের অবস্থায় ফেরত               │
     * └────────────────────────────────────────────────────────────────────────┘
     *
     * @param exchange HttpExchange object
     * @throws IOException response পাঠাতে ব্যর্থ হলে
     */
    private void addRepayment(HttpExchange exchange) throws IOException {

        // Request body পড়া ও parse করা
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
        // Input Validation — ফিল্ড যাচাই
        // ====================================================================
        if (!data.has("loan_id") || data.get("loan_id").isJsonNull() ||
            !data.has("amount_paid") || data.get("amount_paid").isJsonNull()) {

            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Required fields: loan_id, amount_paid.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        int loanId = data.get("loan_id").getAsInt();
        double amountPaid = data.get("amount_paid").getAsDouble();

        // পরিশোধের পরিমাণ ধনাত্মক হতে হবে
        if (amountPaid <= 0) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Payment amount must be a positive number.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        // ====================================================================
        // 🔴 DATABASE TRANSACTION শুরু 🔴
        // ====================================================================
        // Connection object-টি try ব্লকের বাইরে declare করি যাতে
        // catch এবং finally ব্লকেও ব্যবহার করা যায়।
        //
        // null দিয়ে শুরু করি — ডাটাবেস সংযোগ ব্যর্থ হলে null থাকবে,
        // finally-তে null চেক করে সমস্যা এড়ানো যাবে।
        // ====================================================================
        Connection conn = null;

        try {
            conn = DatabaseHelper.getInstance().getConnection();

            // ================================================================
            // setAutoCommit(false) — Transaction শুরু করা
            // ================================================================
            // ডিফল্টে JDBC-তে "Auto Commit" চালু থাকে, অর্থাৎ প্রতিটি
            // SQL statement চালানোর সাথে সাথে স্বয়ংক্রিয়ভাবে commit হয়ে যায়।
            //
            // setAutoCommit(false) বললে আমরা JDBC-কে বলি:
            // "এখন থেকে আমি commit() বা rollback() না বলা পর্যন্ত
            //  কিছু স্থায়ী (permanent) করো না।"
            //
            // PHP-র সমতুল্য: $pdo->beginTransaction()
            //
            // ⚠️ গুরুত্বপূর্ণ: finally ব্লকে setAutoCommit(true) ফেরত দিতে
            // ভুললে পরবর্তী সব query-ও transaction mode-এ চলবে!
            // ================================================================
            conn.setAutoCommit(false);

            // ================================================================
            // ধাপ ১: ঋণ যাচাই করা — SELECT ... FOR UPDATE
            // ================================================================
            // "FOR UPDATE" কেন?
            // ──────────────────
            // এটি একটি "Pessimistic Lock" (হতাশাবাদী তালা)।
            //
            // ধরুন রহিম ও করিম একই সময়ে একই ঋণে পরিশোধ করছে:
            //   রহিম: SELECT → amount=500, total_paid=300 → status='Partially Paid'
            //   করিম: SELECT → amount=500, total_paid=300 → status='Partially Paid'
            //   রহিম: INSERT 200 → total=500 → UPDATE status='Settled'
            //   করিম: INSERT 100 → total=600 → UPDATE status='Settled'
            //   → মোট পরিশোধ ৬০০, কিন্তু status ঠিক আছে (Settled)
            //   → তবে total_paid calculation ভুল হতে পারত!
            //
            // FOR UPDATE → রহিমের SELECT-এর পর row-তে lock লাগায়।
            // করিমের SELECT অপেক্ষা করবে যতক্ষণ না রহিমের transaction শেষ হয়।
            // এভাবে "race condition" প্রতিরোধ হয়।
            //
            // PHP-তে: $pdo->prepare("SELECT ... FOR UPDATE")
            // (PHP-র repayments.php-তেও FOR UPDATE ব্যবহৃত হয়েছে)
            // ================================================================
            String sqlCheck = "SELECT loan_id, amount, status FROM loans WHERE loan_id = ? FOR UPDATE";

            PreparedStatement psCheck = conn.prepareStatement(sqlCheck);
            psCheck.setInt(1, loanId);
            ResultSet rsCheck = psCheck.executeQuery();

            // ঋণ পাওয়া যায়নি → rollback করে error ফেরত দাও
            if (!rsCheck.next()) {
                /*
                 * rollback() — Transaction বাতিল করা
                 * ────────────────────────────────────
                 * এখনো কিছু INSERT/UPDATE হয়নি, তাই rollback-এ
                 * আসলে কিছু বাতিল হচ্ছে না। তবে best practice হলো
                 * transaction শুরু করলে সবসময় commit বা rollback করা।
                 *
                 * PHP-র সমতুল্য: $pdo->rollBack()
                 */
                conn.rollback();
                rsCheck.close();
                psCheck.close();

                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Loan not found. Cannot add repayment to a non-existent loan.");
                CorsUtil.sendJsonResponse(exchange, 404, gson.toJson(error));
                return;
            }

            // ঋণ ইতিমধ্যে Settled কিনা যাচাই
            String currentStatus = rsCheck.getString("status");
            if ("Settled".equals(currentStatus)) {
                conn.rollback();
                rsCheck.close();
                psCheck.close();

                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "This loan is already fully settled. No further repayments needed.");
                CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
                return;
            }

            // মূল ঋণের পরিমাণ বের করা
            double loanAmount = rsCheck.getDouble("amount");
            rsCheck.close();
            psCheck.close();

            // ================================================================
            // ধাপ ২: পরিশোধ INSERT করা
            // ================================================================
            // repayments table-এ নতুন row ঢোকানো।
            // payment_date কলাম DEFAULT CURRENT_TIMESTAMP ব্যবহার করবে,
            // তাই আমরা সেটি INSERT-এ দিচ্ছি না।
            //
            // ⚠️ গুরুত্বপূর্ণ: এই INSERT এখনো "permanent" নয়!
            // setAutoCommit(false) থাকায় এটি "pending" (অপেক্ষমাণ) অবস্থায়
            // আছে। commit() কল না হওয়া পর্যন্ত অন্য কোনো connection
            // এই নতুন row দেখতে পাবে না (Isolation property)।
            // ================================================================
            String sqlInsert = "INSERT INTO repayments (loan_id, amount_paid) VALUES (?, ?)";

            PreparedStatement psInsert = conn.prepareStatement(sqlInsert,
                    PreparedStatement.RETURN_GENERATED_KEYS);
            psInsert.setInt(1, loanId);
            psInsert.setDouble(2, amountPaid);
            psInsert.executeUpdate();

            // নতুন repayment-এর auto-generated ID পড়া
            ResultSet generatedKeys = psInsert.getGeneratedKeys();
            int repaymentId = 0;
            if (generatedKeys.next()) {
                repaymentId = generatedKeys.getInt(1);
            }
            generatedKeys.close();
            psInsert.close();

            // ================================================================
            // ধাপ ৩: মোট পরিশোধ গণনা করা — SELECT SUM
            // ================================================================
            // এই ঋণের সব repayment-এর amount_paid-এর যোগফল বের করি,
            // ধাপ ২-এ INSERT করা নতুন repayment-টিও অন্তর্ভুক্ত হবে
            // (কারণ একই transaction-এর ভেতরে, তাই এই connection-এ দেখা যায়)।
            //
            // COALESCE(SUM(...), 0):
            //   SUM() → NULL ফেরত দেয় যদি কোনো row না থাকে
            //   COALESCE → NULL-কে 0 দিয়ে প্রতিস্থাপন করে
            //   তবে এই মুহূর্তে NULL হওয়া উচিত নয়, কারণ আমরা মাত্র INSERT করলাম
            //   — তবুও defensive programming-এ COALESCE রাখা ভালো অভ্যাস।
            //
            // PHP-র সমতুল্য:
            //   $stmt = $pdo->prepare("SELECT COALESCE(SUM(amount_paid), 0) AS total_paid ...");
            //   $result = $stmt->fetch();
            //   $total_paid = (float) $result['total_paid'];
            // ================================================================
            String sqlSum = "SELECT COALESCE(SUM(amount_paid), 0) AS total_paid " +
                            "FROM repayments WHERE loan_id = ?";

            PreparedStatement psSum = conn.prepareStatement(sqlSum);
            psSum.setInt(1, loanId);
            ResultSet rsSum = psSum.executeQuery();
            rsSum.next();  // SUM() সবসময় একটি row ফেরত দেয়

            double totalPaid = rsSum.getDouble("total_paid");
            rsSum.close();
            psSum.close();

            // ================================================================
            // ধাপ ৪: নতুন স্ট্যাটাস নির্ধারণ করা
            // ================================================================
            // তুলনা যুক্তি (Comparison Logic):
            //
            //   total_paid >= loanAmount → "Settled" (সম্পূর্ণ পরিশোধিত)
            //     >= ব্যবহার করি কারণ কেউ ভুলবশত অতিরিক্ত পরিশোধ করতে পারে
            //     (যেমন ঋণ ৫০০, পরিশোধ ৬০০)
            //
            //   total_paid > 0 → "Partially Paid" (আংশিক পরিশোধিত)
            //     কিছু পরিশোধ হয়েছে কিন্তু পুরোটা নয়
            //
            //   otherwise → "Unpaid" (অপরিশোধিত)
            //     তাত্ত্বিকভাবে এখানে আসা সম্ভব নয় কারণ আমরা মাত্র
            //     পরিশোধ INSERT করেছি, কিন্তু defensive programming-এ রাখা হলো
            //
            // PHP-র সমতুল্য:
            //   if ($total_paid >= $loan_amount) $new_status = 'Settled';
            //   elseif ($total_paid > 0) $new_status = 'Partially Paid';
            //   else $new_status = 'Unpaid';
            // ================================================================
            String newStatus;
            if (totalPaid >= loanAmount) {
                newStatus = "Settled";
            } else if (totalPaid > 0) {
                newStatus = "Partially Paid";
            } else {
                newStatus = "Unpaid";
            }

            // ================================================================
            // ধাপ ৫: ঋণের স্ট্যাটাস UPDATE করা
            // ================================================================
            // loans table-এর status কলাম আপডেট করি।
            // এটি Transaction-এর শেষ SQL operation।
            //
            // ⚠️ এই UPDATE-ও এখনো "pending" — commit() না হওয়া পর্যন্ত
            // অন্য connection-গুলো পুরনো status দেখবে।
            //
            // PHP-র সমতুল্য:
            //   $stmt = $pdo->prepare("UPDATE loans SET status = :status WHERE loan_id = :loan_id");
            //   $stmt->execute([':status' => $new_status, ':loan_id' => $loan_id]);
            // ================================================================
            String sqlUpdate = "UPDATE loans SET status = ? WHERE loan_id = ?";

            PreparedStatement psUpdate = conn.prepareStatement(sqlUpdate);
            psUpdate.setString(1, newStatus);
            psUpdate.setInt(2, loanId);
            psUpdate.executeUpdate();
            psUpdate.close();

            // ================================================================
            // 🟢 COMMIT — Transaction সফলভাবে সম্পন্ন! 🟢
            // ================================================================
            // conn.commit() MySQL-কে বলে:
            // "সবকিছু ঠিক আছে। ধাপ ২-এর INSERT এবং ধাপ ৫-এর UPDATE —
            //  দুটোই স্থায়ী (permanent) করো।"
            //
            // commit()-এর পর:
            //   ১. INSERT ও UPDATE ডিস্কে লেখা হয় (Durability)
            //   ২. অন্য connection-গুলো নতুন ডেটা দেখতে পায় (Isolation শেষ)
            //   ৩. FOR UPDATE lock মুক্ত হয় (অন্যরা এখন row access করতে পারে)
            //
            // PHP-র সমতুল্য: $pdo->commit()
            // ================================================================
            conn.commit();

            // ================================================================
            // সফল Response পাঠানো
            // ================================================================
            // PHP-র repayments.php-র response-এর হুবহু একই ফরম্যাট:
            // {
            //   "success": true,
            //   "message": "Repayment logged successfully!",
            //   "repayment_id": 7,
            //   "total_paid": 700.00,
            //   "loan_amount": 1000.00,
            //   "remaining": 300.00,
            //   "new_status": "Partially Paid"
            // }
            //
            // remaining গণনা:
            //   Math.round((loanAmount - totalPaid) * 100.0) / 100.0
            //   → ২ দশমিক স্থান পর্যন্ত গোল করা
            //   → PHP-র round($value, 2) এর সমতুল্য
            // ================================================================
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Repayment logged successfully!");
            response.addProperty("repayment_id", repaymentId);
            response.addProperty("total_paid", totalPaid);
            response.addProperty("loan_amount", loanAmount);
            response.addProperty("remaining", Math.round((loanAmount - totalPaid) * 100.0) / 100.0);
            response.addProperty("new_status", newStatus);

            CorsUtil.sendJsonResponse(exchange, 201, gson.toJson(response));

        } catch (SQLException e) {
            // ================================================================
            // 🔴 ROLLBACK — ত্রুটি! সব বাতিল করো! 🔴
            // ================================================================
            // কোনো ধাপে SQLException হলে পুরো transaction rollback করি।
            //
            // rollback() কী করে?
            // ──────────────────────
            // setAutoCommit(false)-এর পর থেকে যত INSERT/UPDATE/DELETE
            // করা হয়েছে, সব বাতিল (undo) করে দেয়। ডাটাবেস transaction
            // শুরুর আগের অবস্থায় ফিরে যায়।
            //
            // বাস্তব উদাহরণ:
            //   ধাপ ২-এ INSERT সফল হয়েছে
            //   ধাপ ৫-এ UPDATE ব্যর্থ হলো
            //   → rollback() → ধাপ ২-এর INSERT-ও বাতিল হবে!
            //   → ডাটাবেসে কোনো পরিবর্তন নেই
            //   → এটি হলো "Atomicity" — সব অথবা কিছুই নয়!
            //
            // PHP-র সমতুল্য: $pdo->rollBack()
            // ================================================================
            if (conn != null) {
                try {
                    conn.rollback();
                    System.err.println("⚠️ Transaction rollback সফল — সব পরিবর্তন বাতিল।");
                } catch (SQLException rollbackEx) {
                    // rollback-ও ব্যর্থ হলে (অত্যন্ত বিরল, সাধারণত connection মরে গেলে)
                    System.err.println("❌ Rollback-ও ব্যর্থ! কানেকশন সমস্যা: " + rollbackEx.getMessage());
                }
            }

            // ক্লায়েন্টকে error response পাঠানো
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Failed to process repayment: " + e.getMessage());
            CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));

        } finally {
            // ================================================================
            // FINALLY ব্লক — cleanup (পরিষ্কার-পরিচ্ছন্নতা)
            // ================================================================
            // finally ব্লক সবসময় চলে — try সফল হোক বা catch-এ ত্রুটি হোক।
            //
            // setAutoCommit(true) ফেরত দেওয়া অত্যন্ত গুরুত্বপূর্ণ!
            // কেন? আমাদের DatabaseHelper Singleton — একই Connection object
            // পুনঃব্যবহৃত হয়। যদি autoCommit false রয়ে যায়, তাহলে
            // পরবর্তী সব API request-ও transaction mode-এ চলবে এবং
            // commit না করলে কিছুই save হবে না — এটি একটি মারাত্মক bug!
            //
            // PHP-তে এই সমস্যা নেই কারণ প্রতিটি request-এ নতুন $pdo
            // তৈরি হয় এবং script শেষে destroy হয়। Java-তে Singleton
            // ব্যবহার করায় আমাদের নিজে পরিষ্কার করতে হয়।
            // ================================================================
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException autoCommitEx) {
                    System.err.println("⚠️ Auto-commit ফেরত দিতে সমস্যা: " + autoCommitEx.getMessage());
                }
            }
        }
    }


    // ========================================================================
    // getRepayments() — নির্দিষ্ট ঋণের পরিশোধ ইতিহাস
    // ========================================================================
    /**
     * GET /api/repayments?loan_id=5 → loan 5-এর সব repayment ফেরত দেয়
     *
     * SQL: SELECT repayment_id, loan_id, amount_paid, payment_date
     *      FROM repayments WHERE loan_id = ? ORDER BY payment_date ASC
     *
     * ORDER BY payment_date ASC → কালানুক্রমিক ক্রমে (পুরনোটি আগে)
     * এটি পরিশোধ ইতিহাসের টাইমলাইন দেখাতে সুবিধাজনক:
     *   "এই ৫০০ টাকার ঋণ ৩ কিস্তিতে পরিশোধ হয়েছে:
     *    ১ জানুয়ারি ২০০ টাকা, ১ ফেব্রুয়ারি ১৫০ টাকা, ১০ মার্চ ১৫০ টাকা"
     *
     * @param exchange HttpExchange object
     * @throws IOException response পাঠাতে ব্যর্থ হলে
     */
    private void getRepayments(HttpExchange exchange) throws IOException {

        Map<String, String> params = parseQueryParams(exchange);

        if (!params.containsKey("loan_id")) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Missing required parameter: loan_id");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        int loanId;
        try {
            loanId = Integer.parseInt(params.get("loan_id"));
        } catch (NumberFormatException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "loan_id must be a valid integer.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();

            // ====================================================================
            // SELECT query — পরিশোধ ইতিহাস
            // ====================================================================
            // PHP-র repayments.php-র getRepayments() ফাংশনের হুবহু একই query।
            // ORDER BY payment_date ASC → পুরনো পরিশোধ আগে (কালানুক্রমিক)
            // ====================================================================
            String sql = "SELECT repayment_id, loan_id, amount_paid, payment_date " +
                         "FROM repayments WHERE loan_id = ? ORDER BY payment_date ASC";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, loanId);

                try (ResultSet rs = ps.executeQuery()) {
                    // ================================================================
                    // ResultSet → JSON Array
                    // ================================================================
                    // PHP-তে type casting করা হতো:
                    //   $repayment['repayment_id'] = (int) $repayment['repayment_id'];
                    //   $repayment['amount_paid'] = (float) $repayment['amount_paid'];
                    //
                    // Java-তে getInt(), getDouble() সরাসরি সঠিক type দেয়।
                    // ================================================================
                    JsonArray repaymentsArray = new JsonArray();

                    while (rs.next()) {
                        JsonObject repayment = new JsonObject();

                        repayment.addProperty("repayment_id", rs.getInt("repayment_id"));
                        repayment.addProperty("loan_id", rs.getInt("loan_id"));
                        repayment.addProperty("amount_paid", rs.getDouble("amount_paid"));

                        // payment_date → TIMESTAMP — getString() দিয়ে
                        // "2024-01-15 14:30:00" ফরম্যাটে JSON-এ পাঠাই
                        repayment.addProperty("payment_date", rs.getString("payment_date"));

                        repaymentsArray.add(repayment);
                    }

                    // সফল response
                    JsonObject response = new JsonObject();
                    response.addProperty("success", true);
                    response.add("repayments", repaymentsArray);

                    CorsUtil.sendJsonResponse(exchange, 200, gson.toJson(response));
                }
            }

        } catch (SQLException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Failed to fetch repayments: " + e.getMessage());
            CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
        }
    }


    // ========================================================================
    // সহায়ক Methods (Helper Methods)
    // ========================================================================

    /** URL query string parse করে key-value Map ফেরত দেয়। */
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
