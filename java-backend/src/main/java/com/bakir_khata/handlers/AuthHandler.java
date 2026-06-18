package com.bakir_khata.handlers;

/*
 * ============================================================================
 * ফাইল: AuthHandler.java — ব্যবহারকারী নিবন্ধন ও লগইন (Authentication)
 * ============================================================================
 *
 * উদ্দেশ্য:
 * এই ক্লাসটি PHP-র auth.php ফাইলের সম্পূর্ণ Java রূপান্তর।
 * POST request-এর JSON body-তে "action" ফিল্ড দেখে দুটি কাজের
 * একটি করে:
 *   action = "register" → নতুন ব্যবহারকারী তৈরি (password hash সহ)
 *   action = "login"    → বিদ্যমান ব্যবহারকারীর প্রমাণীকরণ
 *
 * ============================================================================
 * OOP ধারণা: Interface Implementation (ইন্টারফেস বাস্তবায়ন)
 * ============================================================================
 * HttpHandler হলো একটি interface যেখানে একটি method সংজ্ঞায়িত আছে:
 *   void handle(HttpExchange exchange) throws IOException;
 *
 * Interface কী?
 * ─────────────────
 * Interface হলো একটি "চুক্তিপত্র" (contract) — এটি বলে "কী করতে হবে",
 * কিন্তু "কীভাবে করতে হবে" সেটা বলে না। কোনো ক্লাস interface implement
 * করলে সেই method-গুলো বাধ্যতামূলকভাবে লিখতে হয়।
 *
 * তুলনা:
 *   Interface  → বাড়ির নকশা (blueprint) — "এখানে দরজা থাকবে"
 *   Class      → আসল বাড়ি — দরজা কাঠের হবে না লোহার, সেটা ক্লাস ঠিক করে
 *
 * implements keyword:
 *   class AuthHandler implements HttpHandler
 *   → "AuthHandler ক্লাস HttpHandler interface-এর চুক্তি মেনে চলবে"
 *   → অর্থাৎ handle() method অবশ্যই লিখতে হবে
 *
 * ============================================================================
 * BCrypt পাসওয়ার্ড হ্যাশিং (PHP-র password_hash() এর সমতুল্য)
 * ============================================================================
 * BCrypt.hashpw(password, BCrypt.gensalt())
 *   → PHP: password_hash($password, PASSWORD_DEFAULT)
 *
 * BCrypt.checkpw(password, hash)
 *   → PHP: password_verify($password, $hash)
 *
 * BCrypt কেন?
 *   ১. ইচ্ছাকৃতভাবে ধীর → brute-force আক্রমণ কঠিন
 *   ২. স্বয়ংক্রিয় salt → rainbow table আক্রমণ প্রতিরোধ
 *   ৩. PHP-র bcrypt-এর সাথে compatible → একই hash, দুই ভাষায় কাজ করে
 * ============================================================================
 */

// Java HTTP সার্ভার ক্লাস
import com.sun.net.httpserver.HttpHandler;    // interface যা implement করতে হবে
import com.sun.net.httpserver.HttpExchange;   // request-response তথ্য বাহক

// JDBC (Java Database Connectivity) ক্লাস
import java.sql.Connection;                   // ডাটাবেস সংযোগ
import java.sql.PreparedStatement;            // নিরাপদ SQL query (SQL injection প্রতিরোধ)
import java.sql.ResultSet;                    // query ফলাফল পাঠক
import java.sql.SQLException;                // ডাটাবেস ত্রুটি

// Java IO ক্লাস
import java.io.IOException;                   // IO ত্রুটি
import java.io.InputStream;                   // request body পড়ার stream
import java.io.InputStreamReader;             // byte → character রূপান্তর
import java.io.BufferedReader;                // কার্যকর পাঠক (buffered reading)

// Gson — JSON parsing লাইব্রেরি
import com.google.gson.Gson;                  // JSON ↔ Java Object রূপান্তরকারী
import com.google.gson.JsonObject;            // JSON object (key-value জোড়া)
import com.google.gson.JsonParser;            // JSON string parse করে

// BCrypt — পাসওয়ার্ড হ্যাশিং
import org.mindrot.jbcrypt.BCrypt;            // hash ও verify ফাংশন

// আমাদের নিজস্ব ক্লাস
import com.bakir_khata.DatabaseHelper;        // ডাটাবেস সংযোগ ব্যবস্থাপক

/**
 * AuthHandler — /api/auth endpoint-এর HttpHandler implementation।
 *
 * OOP ধারণা → implements (ইন্টারফেস বাস্তবায়ন):
 * "implements HttpHandler" মানে এই ক্লাস HttpHandler interface-এর
 * handle() method-কে নিজের মতো করে লিখবে। Java-র HttpServer
 * request এলে এই handle() method কল করবে — এটি "callback" প্যাটার্ন।
 */
public class AuthHandler implements HttpHandler {

    /*
     * Gson object — JSON parse ও serialize করতে ব্যবহৃত।
     * private → শুধু এই ক্লাসে ব্যবহারযোগ্য (Encapsulation)
     * final   → একবার তৈরি হলে পরিবর্তন করা যাবে না
     *
     * Gson thread-safe, তাই একটি instance পুনঃব্যবহার করা নিরাপদ ও কার্যকর।
     */
    private final Gson gson = new Gson();

    // ========================================================================
    // handle() — HttpHandler interface-এর বাধ্যতামূলক method
    // ========================================================================
    /**
     * HTTP request আসলে Java-র HttpServer এই method কল করে।
     *
     * HttpExchange কী?
     * ──────────────────
     * HttpExchange হলো একটি object যা request ও response-এর সকল তথ্য
     * ধারণ করে। PHP-তে এর সমতুল্য হলো:
     *   $_SERVER['REQUEST_METHOD'] → exchange.getRequestMethod()
     *   file_get_contents('php://input') → exchange.getRequestBody()
     *   echo json_encode($data)  → exchange.getResponseBody().write(bytes)
     *   http_response_code(200)  → exchange.sendResponseHeaders(200, length)
     *
     * @param exchange HTTP request-response তথ্য বাহক
     * @throws IOException IO সংক্রান্ত ত্রুটি হলে
     */
    @Override  // annotation → বলে যে এই method parent/interface থেকে override করা হয়েছে
    public void handle(HttpExchange exchange) throws IOException {

        // ====================================================================
        // ধাপ ১: CORS হেডার সেট করা ও OPTIONS request হ্যান্ডেল করা
        // ====================================================================
        // প্রতিটি handler-এর শুরুতে এই কল বাধ্যতামূলক।
        // OPTIONS request হলে true ফেরত আসে → return করে দিই।
        // ====================================================================
        if (CorsUtil.handleCors(exchange)) return;

        // ====================================================================
        // ধাপ ২: HTTP Method যাচাই
        // ====================================================================
        // auth endpoint শুধু POST গ্রহণ করে।
        // PHP-তে: if ($_SERVER['REQUEST_METHOD'] !== 'POST') { ... }
        //
        // Java-তে String তুলনা করতে .equals() বা .equalsIgnoreCase()
        // ব্যবহার করতে হয়। == operator String-এ reference তুলনা করে,
        // content নয় — এটি Java-র একটি সাধারণ ভুল!
        // ====================================================================
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Method not allowed. Use POST.");
            CorsUtil.sendJsonResponse(exchange, 405, gson.toJson(error));
            return;
        }

        // ====================================================================
        // ধাপ ৩: Request Body (JSON) পড়া ও Parse করা
        // ====================================================================
        // PHP: $data = json_decode(file_get_contents('php://input'), true);
        //
        // Java-তে request body পড়া একটু জটিল:
        //   InputStream → raw byte stream (কাঁচা বাইট প্রবাহ)
        //   InputStreamReader → byte কে character-এ রূপান্তর করে
        //   BufferedReader → line-by-line কার্যকরভাবে পড়ে
        //   StringBuilder → string জোড়া লাগায় (String concatenation-এর চেয়ে দ্রুত)
        //
        // কেন StringBuilder ব্যবহার করি?
        // Java-তে String immutable (পরিবর্তন অযোগ্য)। প্রতিটি + অপারেশনে
        // নতুন String object তৈরি হয়। StringBuilder একই object-এ
        // text যোগ করে — মেমোরি ও পারফরম্যান্সে উন্নত।
        // ====================================================================
        String requestBody = readRequestBody(exchange);

        // ====================================================================
        // ধাপ ৪: JSON parse করা
        // ====================================================================
        JsonObject data;
        try {
            data = JsonParser.parseString(requestBody).getAsJsonObject();
        } catch (Exception e) {
            // JSON parse ব্যর্থ → malformed JSON
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Invalid JSON in request body.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        // ====================================================================
        // ধাপ ৫: "action" ফিল্ড যাচাই ও routing
        // ====================================================================
        // PHP: if ($action === 'register') { ... } elseif ($action === 'login') { ... }
        //
        // Java-তে switch statement ব্যবহার করছি — এটি if-else chain-এর
        // চেয়ে পড়তে সহজ এবং Java 17-এ ভালো পারফরম্যান্স দেয়।
        // ====================================================================
        if (!data.has("action") || data.get("action").isJsonNull()) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Invalid request. Please provide an \"action\" field (\"register\" or \"login\").");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        String action = data.get("action").getAsString();

        switch (action) {
            case "register":
                handleRegister(exchange, data);
                break;
            case "login":
                handleLogin(exchange, data);
                break;
            default:
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Unknown action. Use \"register\" or \"login\".");
                CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
                break;
        }
    }


    // ========================================================================
    // handleRegister() — নতুন ব্যবহারকারী নিবন্ধন
    // ========================================================================
    /**
     * নতুন ব্যবহারকারী তৈরি করে।
     *
     * কর্মপ্রবাহ (Workflow):
     *   ১. full_name, email, password ফিল্ড যাচাই করে
     *   ২. BCrypt দিয়ে password hash করে
     *   ৩. PreparedStatement দিয়ে INSERT query চালায়
     *   ৪. সফল হলে user_id ও full_name ফেরত দেয়
     *   ৫. email duplicate হলে 409 Conflict ফেরত দেয়
     *
     * @param exchange HttpExchange object
     * @param data     Parse করা JSON data
     * @throws IOException response পাঠাতে ব্যর্থ হলে
     */
    private void handleRegister(HttpExchange exchange, JsonObject data) throws IOException {

        // ====================================================================
        // Input Validation — ফিল্ড যাচাই
        // ====================================================================
        // PHP: if (empty(trim($data['full_name'] ?? ''))) { ... }
        // Java: has() দিয়ে key আছে কিনা চেক, তারপর isEmpty()/isBlank() দিয়ে ফাঁকা কিনা
        //
        // getAsString().trim().isEmpty() → ফাঁকা string বা শুধু space আছে কিনা
        // ====================================================================
        String fullName = getStringField(data, "full_name");
        String email = getStringField(data, "email");
        String password = getStringField(data, "password");

        if (fullName == null || fullName.isBlank() ||
            email == null || email.isBlank() ||
            password == null || password.isEmpty()) {

            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "All fields are required: full_name, email, password.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        fullName = fullName.trim();
        email = email.trim();

        // ====================================================================
        // BCrypt দিয়ে পাসওয়ার্ড হ্যাশ করা
        // ====================================================================
        // BCrypt.gensalt() → একটি random salt তৈরি করে
        //   ডিফল্ট cost factor = 10 (2^10 = 1024 iterations)
        //   cost বাড়ালে হ্যাশিং ধীর হয় → brute-force আরও কঠিন
        //
        // BCrypt.hashpw(password, salt) → password + salt মিলিয়ে hash তৈরি করে
        //
        // ফলাফল উদাহরণ: "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
        //   $2a$ → algorithm (bcrypt)
        //   10$  → cost factor
        //   পরের ২২ character → salt
        //   বাকি → hash
        //
        // PHP-র সমতুল্য: $hash = password_hash($password, PASSWORD_DEFAULT);
        // ====================================================================
        String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());

        // ====================================================================
        // JDBC দিয়ে ডাটাবেসে INSERT করা
        // ====================================================================
        // try-with-resources statement:
        // Java 7 থেকে চালু। try (...) ব্লকে declared resource গুলো
        // ব্লক শেষ হলে স্বয়ংক্রিয়ভাবে close() হয় — even if exception
        // throw হয়। এটি finally block-এ close() কল করার চেয়ে নিরাপদ ও পরিষ্কার।
        //
        // PreparedStatement কী?
        // ─────────────────────
        // SQL query-তে ? চিহ্ন রাখি (placeholder)। পরে setString(), setInt()
        // দিয়ে আসল মান বসাই। এভাবে SQL Injection সম্পূর্ণ প্রতিরোধ হয়।
        //
        // PHP-র সমতুল্য:
        //   $stmt = $pdo->prepare("INSERT ... VALUES (:name, :email, :hash)");
        //   $stmt->execute([':name' => $name, ':email' => $email, ':hash' => $hash]);
        //
        // Java-তে:
        //   PreparedStatement ps = conn.prepareStatement("INSERT ... VALUES (?, ?, ?)");
        //   ps.setString(1, name);  // ? নম্বর 1-indexed (PHP-র :name এর বদলে)
        //   ps.setString(2, email);
        //   ps.setString(3, hash);
        //   ps.executeUpdate();
        //
        // RETURN_GENERATED_KEYS → INSERT-এর পর auto-generated ID (user_id) পেতে
        // PHP-র $pdo->lastInsertId() এর সমতুল্য।
        // ====================================================================
        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();

            String sql = "INSERT INTO users (full_name, email, password_hash) VALUES (?, ?, ?)";

            // PreparedStatement.RETURN_GENERATED_KEYS → auto_increment ID ফেরত চাই
            try (PreparedStatement ps = conn.prepareStatement(sql,
                    PreparedStatement.RETURN_GENERATED_KEYS)) {

                // ? placeholder-গুলোতে আসল মান বসানো (1-indexed)
                ps.setString(1, fullName);       // ১ম ? → full_name
                ps.setString(2, email);          // ২য় ? → email
                ps.setString(3, passwordHash);   // ৩য় ? → password_hash

                /*
                 * executeUpdate() কী?
                 * ─────────────────────
                 * INSERT, UPDATE, DELETE query-র জন্য executeUpdate() ব্যবহার হয়।
                 * এটি ফেরত দেয় কতটি row প্রভাবিত হয়েছে (affected rows)।
                 *
                 * executeQuery() → SELECT query-র জন্য (ResultSet ফেরত দেয়)
                 * executeUpdate() → INSERT/UPDATE/DELETE-র জন্য (int ফেরত দেয়)
                 */
                ps.executeUpdate();

                // ====================================================================
                // Auto-generated key (user_id) পড়া
                // ====================================================================
                // getGeneratedKeys() → ResultSet ফেরত দেয় যেখানে
                // auto_increment column-এর মান আছে।
                // rs.next() → পরবর্তী row-তে যায় (true যদি row থাকে)
                // rs.getInt(1) → প্রথম column-এর int মান পড়ে
                //
                // PHP-র সমতুল্য: $newUserId = $pdo->lastInsertId();
                // ====================================================================
                ResultSet generatedKeys = ps.getGeneratedKeys();
                int newUserId = 0;
                if (generatedKeys.next()) {
                    newUserId = generatedKeys.getInt(1);
                }

                // সফল response পাঠানো (PHP-র json_encode() এর সমতুল্য)
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("message", "Registration successful!");
                response.addProperty("user_id", newUserId);
                response.addProperty("full_name", fullName);

                // 201 Created → নতুন resource তৈরি হয়েছে
                CorsUtil.sendJsonResponse(exchange, 201, gson.toJson(response));
            }

        } catch (SQLException e) {
            // ====================================================================
            // ডাটাবেস ত্রুটি হ্যান্ডেল করা
            // ====================================================================
            // SQLState "23000" → integrity constraint violation
            // MySQL-এ এটি UNIQUE constraint violation অন্তর্ভুক্ত করে।
            // email UNIQUE হওয়ায়, duplicate email-এ এই ত্রুটি আসে।
            //
            // PHP-র সমতুল্য:
            //   catch (PDOException $e) {
            //       if ($e->getCode() == 23000) { ... }
            //   }
            // ====================================================================
            if ("23000".equals(e.getSQLState())) {
                // 409 Conflict → resource ইতিমধ্যে আছে
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "This email is already registered. Please use a different email or login.");
                CorsUtil.sendJsonResponse(exchange, 409, gson.toJson(error));
            } else {
                // অন্যান্য ডাটাবেস ত্রুটি
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Registration failed: " + e.getMessage());
                CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
            }
        }
    }


    // ========================================================================
    // handleLogin() — ব্যবহারকারী লগইন (প্রমাণীকরণ)
    // ========================================================================
    /**
     * ব্যবহারকারীর email ও password যাচাই করে লগইন করায়।
     *
     * কর্মপ্রবাহ:
     *   ১. email ও password ফিল্ড যাচাই
     *   ২. SELECT query দিয়ে email-এর user খোঁজা
     *   ৩. BCrypt.checkpw() দিয়ে password যাচাই
     *   ৪. সফল হলে user_id ও full_name ফেরত দেওয়া
     *
     * নিরাপত্তা নোট:
     * "email not found" এবং "wrong password" — উভয়ের জন্য একই ত্রুটি বার্তা
     * দেওয়া হয়। কারণ ভিন্ন বার্তা দিলে আক্রমণকারী বুঝতে পারবে কোন
     * email গুলো নিবন্ধিত (user enumeration attack)।
     *
     * @param exchange HttpExchange object
     * @param data     Parse করা JSON data
     * @throws IOException response পাঠাতে ব্যর্থ হলে
     */
    private void handleLogin(HttpExchange exchange, JsonObject data) throws IOException {

        // Input validation
        String email = getStringField(data, "email");
        String password = getStringField(data, "password");

        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Email and password are required.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        email = email.trim();

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();

            // ====================================================================
            // PreparedStatement দিয়ে SELECT query
            // ====================================================================
            // SQL: SELECT user_id, full_name, email, password_hash FROM users WHERE email = ?
            //
            // ? placeholder → email-এর মান নিরাপদে বসবে (SQL Injection প্রতিরোধ)
            //
            // PHP-র সমতুল্য:
            //   $stmt = $pdo->prepare("SELECT ... WHERE email = :email");
            //   $stmt->execute([':email' => $email]);
            //   $user = $stmt->fetch();
            // ====================================================================
            String sql = "SELECT user_id, full_name, email, password_hash FROM users WHERE email = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, email);

                /*
                 * executeQuery() → SELECT query-র জন্য ব্যবহৃত
                 * ─────────────────────────────────────────────────
                 * ফেরত দেয়: ResultSet → query ফলাফলের "cursor" (পড়ার যন্ত্র)
                 *
                 * ResultSet কীভাবে কাজ করে?
                 *   ১. প্রথমে cursor ফলাফলের আগে থাকে (before first row)
                 *   ২. rs.next() → পরবর্তী row-তে যায়, row থাকলে true ফেরত দেয়
                 *   ৩. rs.getString("column_name") → সেই row-র column-এর মান পড়ে
                 *   ৪. আর row না থাকলে rs.next() false ফেরত দেয়
                 *
                 * PHP-র $stmt->fetch() এর সমতুল্য।
                 * fetch() একটি row ফেরত দেয় বা false। এখানে rs.next() একই কাজ করে।
                 */
                try (ResultSet rs = ps.executeQuery()) {

                    if (rs.next()) {
                        // ব্যবহারকারী পাওয়া গেছে — password যাচাই করি
                        String storedHash = rs.getString("password_hash");

                        /*
                         * BCrypt.checkpw(plaintext, hash) কীভাবে কাজ করে?
                         * ──────────────────────────────────────────────────
                         * ১. stored hash থেকে salt এবং cost factor বের করে
                         * ২. plaintext password-কে সেই salt ও cost দিয়ে hash করে
                         * ৩. নতুন hash এবং stored hash মিলে গেলে true ফেরত দেয়
                         *
                         * PHP-র সমতুল্য: password_verify($password, $hash)
                         *
                         * গুরুত্বপূর্ণ: সরাসরি string comparison (==) দিয়ে
                         * password যাচাই করা সম্পূর্ণ ভুল ও অনিরাপদ!
                         */
                        if (BCrypt.checkpw(password, storedHash)) {
                            // ✅ লগইন সফল!
                            JsonObject response = new JsonObject();
                            response.addProperty("success", true);
                            response.addProperty("message", "Login successful!");
                            response.addProperty("user_id", rs.getInt("user_id"));
                            response.addProperty("full_name", rs.getString("full_name"));

                            CorsUtil.sendJsonResponse(exchange, 200, gson.toJson(response));
                        } else {
                            // ❌ password ভুল — ইচ্ছাকৃতভাবে অস্পষ্ট বার্তা
                            JsonObject error = new JsonObject();
                            error.addProperty("success", false);
                            error.addProperty("message", "Invalid email or password.");
                            CorsUtil.sendJsonResponse(exchange, 401, gson.toJson(error));
                        }
                    } else {
                        // ❌ email পাওয়া যায়নি — একই অস্পষ্ট বার্তা (নিরাপত্তা)
                        JsonObject error = new JsonObject();
                        error.addProperty("success", false);
                        error.addProperty("message", "Invalid email or password.");
                        CorsUtil.sendJsonResponse(exchange, 401, gson.toJson(error));
                    }
                }
            }

        } catch (SQLException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Login failed due to server error: " + e.getMessage());
            CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
        }
    }


    // ========================================================================
    // readRequestBody() — HTTP Request Body পড়ার সহায়ক Method
    // ========================================================================
    /**
     * request body (JSON string) পড়ে String হিসেবে ফেরত দেয়।
     *
     * Java IO Stream হায়ারার্কি (একটু জটিল, কিন্তু বুঝতে হবে):
     * ──────────────────────────────────────────────────────────────
     *   InputStream (byte stream)
     *       ↓ InputStreamReader দিয়ে wrap করি
     *   Reader (character stream)
     *       ↓ BufferedReader দিয়ে wrap করি
     *   BufferedReader (কার্যকর line-by-line পাঠক)
     *
     * কেন এত layer?
     *   - InputStream → raw byte পড়ে (0101010...)
     *   - InputStreamReader → byte-কে character-এ রূপান্তর করে (UTF-8 → "হ্যালো")
     *   - BufferedReader → buffer ব্যবহার করে একবারে বেশি data পড়ে (দ্রুত)
     *
     * PHP-তে এত কিছু লাগে না: $raw = file_get_contents('php://input');
     * Java-তে আমাদের প্রতিটি layer নিজে সেটআপ করতে হয়।
     *
     * @param exchange HttpExchange object
     * @return request body string
     * @throws IOException পড়তে ব্যর্থ হলে
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        // exchange.getRequestBody() → InputStream ফেরত দেয়
        // PHP-র php://input এর সমতুল্য
        InputStream inputStream = exchange.getRequestBody();

        // InputStreamReader → byte stream-কে character stream-এ রূপান্তর করে
        InputStreamReader isr = new InputStreamReader(inputStream, "UTF-8");

        // BufferedReader → কার্যকর পাঠক, readLine() method দেয়
        BufferedReader reader = new BufferedReader(isr);

        // StringBuilder → দক্ষভাবে string জোড়া লাগায়
        StringBuilder sb = new StringBuilder();
        String line;

        // প্রতিটি line পড়ে StringBuilder-এ যোগ করি
        // readLine() → null ফেরত দেয় যখন stream শেষ
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }

        reader.close();

        // toString() → StringBuilder-এর বিষয়বস্তু String হিসেবে ফেরত দেয়
        return sb.toString();
    }


    // ========================================================================
    // getStringField() — JSON থেকে String ফিল্ড নিরাপদে পড়ার সহায়ক
    // ========================================================================
    /**
     * JsonObject থেকে একটি string ফিল্ড নিরাপদে পড়ে।
     * key না থাকলে বা null হলে null ফেরত দেয় (NullPointerException এড়ায়)।
     *
     * OOP ধারণা → Defensive Programming (রক্ষণাত্মক প্রোগ্রামিং):
     * সরাসরি data.get("key").getAsString() কল করলে, key না থাকলে
     * NullPointerException throw হবে এবং সার্ভার crash করবে।
     * এই method সেই ঝুঁকি দূর করে।
     *
     * @param data JsonObject যেখান থেকে পড়ব
     * @param key  ফিল্ডের নাম
     * @return ফিল্ডের মান, অথবা null যদি না থাকে
     */
    private String getStringField(JsonObject data, String key) {
        if (data.has(key) && !data.get(key).isJsonNull()) {
            return data.get(key).getAsString();
        }
        return null;
    }
}
