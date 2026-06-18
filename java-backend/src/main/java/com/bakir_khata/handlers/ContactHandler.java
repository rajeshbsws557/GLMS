package com.bakir_khata.handlers;

/*
 * ============================================================================
 * ফাইল: ContactHandler.java — যোগাযোগ ব্যবস্থাপনা (Contact Management)
 * ============================================================================
 *
 * উদ্দেশ্য:
 * এই ক্লাসটি PHP-র contacts.php ফাইলের সম্পূর্ণ Java রূপান্তর।
 * দুটি HTTP method সমর্থন করে:
 *   GET  /api/contacts?user_id=1 → নির্দিষ্ট ব্যবহারকারীর সব contact দেখা
 *   POST /api/contacts           → নতুন contact যোগ করা
 *
 * ============================================================================
 * OOP ধারণা: switch Statement দিয়ে Method Routing
 * ============================================================================
 * PHP-তে: switch ($_SERVER['REQUEST_METHOD']) { case 'GET': ... case 'POST': ... }
 * Java-তে: switch (exchange.getRequestMethod()) { case "GET": ... case "POST": ... }
 *
 * এটি "Strategy Pattern"-এর একটি সরল রূপ — request method অনুযায়ী
 * ভিন্ন কৌশল (strategy) নির্বাচন করা হয়।
 *
 * ============================================================================
 * JDBC ধারণা: Query Parameter Extraction
 * ============================================================================
 * PHP-তে GET parameter পাওয়া সহজ: $_GET['user_id']
 * Java-তে URL থেকে ম্যানুয়ালি parse করতে হয়:
 *   URI: /api/contacts?user_id=1&sort=name
 *   getQuery() → "user_id=1&sort=name"
 *   split("&") → ["user_id=1", "sort=name"]
 *   split("=") → key-value জোড়ায় ভাগ করা
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

import java.util.HashMap;   // key-value জোড়া সংরক্ষণের জন্য Map implementation
import java.util.Map;       // Map interface
import java.util.ArrayList; // dynamic array (PHP-র array()-র সমতুল্য)
import java.util.List;      // List interface

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;

import com.bakir_khata.DatabaseHelper;

/**
 * ContactHandler — /api/contacts endpoint-এর handler।
 *
 * implements HttpHandler → HttpHandler interface-এর handle() method
 * বাধ্যতামূলকভাবে লিখতে হবে। এটি OOP-র "Interface Segregation Principle"
 * অনুসরণ করে — একটি interface-এ শুধু একটি method, যাতে implementation
 * সহজ ও পরিষ্কার থাকে।
 */
public class ContactHandler implements HttpHandler {

    private final Gson gson = new Gson();

    // ========================================================================
    // handle() — মূল entry point (প্রতিটি request-এ কল হয়)
    // ========================================================================
    @Override
    public void handle(HttpExchange exchange) throws IOException {

        // CORS হেডার সেট + OPTIONS request হ্যান্ডেল
        if (CorsUtil.handleCors(exchange)) return;

        // ====================================================================
        // HTTP Method Routing — switch statement ব্যবহার করে
        // ====================================================================
        // exchange.getRequestMethod() → "GET", "POST", "PUT", "DELETE" ইত্যাদি
        //
        // PHP-র সমতুল্য: switch ($_SERVER['REQUEST_METHOD']) { ... }
        //
        // OOP নোট: switch statement বনাম if-else chain:
        //   switch → নির্দিষ্ট মান তুলনার জন্য পরিষ্কার ও দ্রুত
        //   if-else → জটিল condition-এর জন্য ভালো
        // ====================================================================
        String method = exchange.getRequestMethod().toUpperCase();

        switch (method) {
            case "GET":
                getContacts(exchange);
                break;
            case "POST":
                addContact(exchange);
                break;
            default:
                // 405 Method Not Allowed
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Method not allowed. Use GET or POST.");
                CorsUtil.sendJsonResponse(exchange, 405, gson.toJson(error));
                break;
        }
    }


    // ========================================================================
    // getContacts() — নির্দিষ্ট ব্যবহারকারীর সব contact দেখানো
    // ========================================================================
    /**
     * GET /api/contacts?user_id=1 → user 1-এর সব contact ফেরত দেয়
     *
     * SQL: SELECT contact_id, contact_name, phone_number
     *      FROM contacts WHERE user_id = ? ORDER BY contact_name ASC
     *
     * JDBC ধারণা — ResultSet Iteration:
     * ──────────────────────────────────
     * ResultSet একটি "cursor" — ডাটাবেসের ফলাফল row-by-row পড়তে দেয়।
     *   while (rs.next()) → পরবর্তী row-তে যায়
     *   rs.getInt("col")  → int মান পড়ে
     *   rs.getString("col") → string মান পড়ে
     *
     * PHP-র $stmt->fetchAll() এখানে while loop দিয়ে simulate করা হয়।
     * PHP fetchAll() একবারে সব row দেয়, Java-তে একটি একটি করে পড়ি।
     *
     * @param exchange HttpExchange object
     * @throws IOException response পাঠাতে ব্যর্থ হলে
     */
    private void getContacts(HttpExchange exchange) throws IOException {

        // ====================================================================
        // URL থেকে Query Parameter বের করা
        // ====================================================================
        // PHP-তে: $_GET['user_id']
        //
        // Java-তে URL.getQuery() → "user_id=1&other=value" (raw query string)
        // আমাদের নিজে parse করতে হয়। parseQueryParams() method এটি করে।
        //
        // exchange.getRequestURI() → URI object ফেরত দেয়
        // URI.getQuery() → query string ফেরত দেয় (? চিহ্নের পরের অংশ)
        //
        // উদাহরণ: /api/contacts?user_id=1
        //   getRequestURI() → /api/contacts?user_id=1
        //   getQuery()      → "user_id=1"
        // ====================================================================
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
            // Integer.parseInt() → String-কে int-এ রূপান্তর করে
            // PHP-তে: (int) $_GET['user_id']
            // ভুল মান দিলে NumberFormatException throw হয়
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
            // PreparedStatement → নিরাপদ SELECT query
            // ====================================================================
            // ORDER BY contact_name ASC → নাম অনুযায়ী বর্ণানুক্রমে সাজানো (A→Z)
            // PHP-র সমতুল্য query — হুবহু একই SQL।
            // ====================================================================
            String sql = "SELECT contact_id, contact_name, phone_number " +
                         "FROM contacts WHERE user_id = ? ORDER BY contact_name ASC";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);  // ১ম ? → user_id (integer)

                try (ResultSet rs = ps.executeQuery()) {

                    // ================================================================
                    // ResultSet থেকে JSON Array তৈরি করা
                    // ================================================================
                    // PHP-তে: $contacts = $stmt->fetchAll(); — একলাইনে সব পাওয়া যায়
                    //
                    // Java-তে আমাদের while loop চালিয়ে একটি একটি row পড়তে হয়
                    // এবং প্রতিটি row-কে JsonObject-এ রূপান্তর করে JsonArray-তে
                    // যোগ করতে হয়।
                    //
                    // JsonArray → JSON-এ array ([ ... ])
                    // JsonObject → JSON-এ object ({ ... })
                    //
                    // Java Collections Framework:
                    //   List<Map<String,Object>> → PHP-র array of associative arrays
                    //   আমরা Gson-এর JsonArray/JsonObject ব্যবহার করছি সরাসরি।
                    // ================================================================
                    JsonArray contactsArray = new JsonArray();

                    while (rs.next()) {
                        JsonObject contact = new JsonObject();
                        contact.addProperty("contact_id", rs.getInt("contact_id"));
                        contact.addProperty("contact_name", rs.getString("contact_name"));

                        // phone_number nullable — getString() null ফেরত দিতে পারে
                        String phone = rs.getString("phone_number");
                        if (phone != null) {
                            contact.addProperty("phone_number", phone);
                        } else {
                            contact.add("phone_number", null);  // JSON-এ null
                        }

                        contactsArray.add(contact);
                    }

                    // সফল response
                    JsonObject response = new JsonObject();
                    response.addProperty("success", true);
                    response.add("contacts", contactsArray);

                    CorsUtil.sendJsonResponse(exchange, 200, gson.toJson(response));
                }
            }

        } catch (SQLException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Failed to fetch contacts: " + e.getMessage());
            CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
        }
    }


    // ========================================================================
    // addContact() — নতুন contact যোগ করা
    // ========================================================================
    /**
     * POST /api/contacts → নতুন contact তৈরি করে
     *
     * প্রত্যাশিত JSON Body:
     * {
     *   "user_id": 1,
     *   "contact_name": "রহিম",
     *   "phone_number": "+880-1712345678"  // ঐচ্ছিক
     * }
     *
     * JDBC ধারণা — Foreign Key Violation:
     * ─────────────────────────────────────
     * contacts.user_id → users.user_id (FOREIGN KEY)
     * যদি এমন user_id দেওয়া হয় যা users table-এ নেই, তাহলে
     * MySQL ত্রুটি দেবে (SQLState 23000)। এটি "referential integrity" —
     * ডাটাবেস নিজে নিশ্চিত করে যে সম্পর্ক সঠিক আছে।
     *
     * @param exchange HttpExchange object
     * @throws IOException response পাঠাতে ব্যর্থ হলে
     */
    private void addContact(HttpExchange exchange) throws IOException {

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

        // Input validation
        if (!data.has("user_id") || data.get("user_id").isJsonNull() ||
            !data.has("contact_name") || data.get("contact_name").isJsonNull() ||
            data.get("contact_name").getAsString().trim().isEmpty()) {

            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Required fields: user_id, contact_name.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        int userId = data.get("user_id").getAsInt();
        String contactName = data.get("contact_name").getAsString().trim();

        // phone_number ঐচ্ছিক (optional) — না থাকলে null
        String phoneNumber = null;
        if (data.has("phone_number") && !data.get("phone_number").isJsonNull()) {
            phoneNumber = data.get("phone_number").getAsString().trim();
            if (phoneNumber.isEmpty()) phoneNumber = null;
        }

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();

            // ====================================================================
            // INSERT query — নতুন contact ডাটাবেসে ঢোকানো
            // ====================================================================
            // PreparedStatement-এ nullable ফিল্ড হ্যান্ডেল করা:
            //   phoneNumber null হলে → ps.setNull(3, java.sql.Types.VARCHAR)
            //   phoneNumber আছে →     ps.setString(3, phoneNumber)
            //
            // PHP-তে null সরাসরি bind করা যায়:
            //   $stmt->execute([':phone' => null]);
            //
            // Java-তে setNull() ব্যবহার করতে হয়, কারণ setString(3, null)
            // কিছু JDBC driver-এ সমস্যা তৈরি করতে পারে।
            // ====================================================================
            String sql = "INSERT INTO contacts (user_id, contact_name, phone_number) VALUES (?, ?, ?)";

            try (PreparedStatement ps = conn.prepareStatement(sql,
                    PreparedStatement.RETURN_GENERATED_KEYS)) {

                ps.setInt(1, userId);
                ps.setString(2, contactName);

                // nullable ফিল্ড হ্যান্ডেল করা
                if (phoneNumber != null) {
                    ps.setString(3, phoneNumber);
                } else {
                    // setNull() → ডাটাবেসে NULL মান সেট করে
                    // java.sql.Types.VARCHAR → কোন ধরনের column-এ NULL বসছে
                    ps.setNull(3, java.sql.Types.VARCHAR);
                }

                ps.executeUpdate();

                // Auto-generated contact_id পড়া
                ResultSet generatedKeys = ps.getGeneratedKeys();
                int newContactId = 0;
                if (generatedKeys.next()) {
                    newContactId = generatedKeys.getInt(1);
                }

                // 201 Created response
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("message", "Contact added successfully!");
                response.addProperty("contact_id", newContactId);

                CorsUtil.sendJsonResponse(exchange, 201, gson.toJson(response));
            }

        } catch (SQLException e) {
            // Foreign Key violation → user_id অবৈধ
            if ("23000".equals(e.getSQLState())) {
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Invalid user_id. The specified user does not exist.");
                CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            } else {
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Failed to add contact: " + e.getMessage());
                CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
            }
        }
    }


    // ========================================================================
    // parseQueryParams() — URL Query String থেকে প্যারামিটার Parse করা
    // ========================================================================
    /**
     * URL-এর query string parse করে key-value Map ফেরত দেয়।
     *
     * উদাহরণ:
     *   Input:  "user_id=1&sort=name"
     *   Output: {user_id: "1", sort: "name"}
     *
     * PHP-তে $_GET superglobal এটি স্বয়ংক্রিয়ভাবে করে দেয়।
     * Java-তে আমাদের ম্যানুয়ালি parse করতে হয়।
     *
     * OOP ধারণা → Map Interface:
     * ─────────────────────────────
     * Map<K,V> হলো key-value জোড়া সংরক্ষণের interface।
     * HashMap হলো Map-এর একটি implementation যা hash table ব্যবহার করে
     * দ্রুত lookup (O(1)) প্রদান করে।
     *
     * PHP-র associative array ($arr['key'] = 'value') এর সমতুল্য।
     *
     * @param exchange HttpExchange object
     * @return Map<String,String> — query parameter-এর key-value জোড়া
     */
    private Map<String, String> parseQueryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();

        // getQuery() → "user_id=1&sort=name" বা null (query নেই)
        String query = exchange.getRequestURI().getQuery();

        if (query != null && !query.isEmpty()) {
            // "&" দিয়ে split → ["user_id=1", "sort=name"]
            String[] pairs = query.split("&");

            for (String pair : pairs) {
                // "=" দিয়ে split → ["user_id", "1"]
                String[] keyValue = pair.split("=", 2);  // 2 → সর্বোচ্চ ২ ভাগ

                if (keyValue.length == 2) {
                    params.put(keyValue[0], keyValue[1]);
                } else if (keyValue.length == 1) {
                    params.put(keyValue[0], "");  // মান ছাড়া key
                }
            }
        }
        return params;
    }


    // ========================================================================
    // readRequestBody() — Request Body পড়ার সহায়ক Method
    // ========================================================================
    /**
     * HTTP request body সম্পূর্ণ পড়ে String হিসেবে ফেরত দেয়।
     * AuthHandler-এ বিস্তারিত ব্যাখ্যা আছে — এখানে সংক্ষিপ্ত সংস্করণ।
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
}
