package com.bakir_khata.handlers;

/*
 * ============================================================================
 * ফাইল: CorsUtil.java — CORS ইউটিলিটি ক্লাস (Cross-Origin Resource Sharing)
 * ============================================================================
 *
 * উদ্দেশ্য:
 * এই ক্লাসটি সকল API response-এ CORS হেডার যোগ করে এবং OPTIONS
 * preflight request গুলো হ্যান্ডেল করে। এটি PHP-র db.php-তে থাকা
 * header() কলগুলোর সমতুল্য।
 *
 * ============================================================================
 * CORS কী? (Cross-Origin Resource Sharing)
 * ============================================================================
 * যখন frontend (যেমন http://localhost:5500) এবং backend (http://localhost:8080)
 * আলাদা origin-এ (ডোমেইন/পোর্ট) থাকে, তখন ব্রাউজার নিরাপত্তার কারণে
 * backend-এ request পাঠাতে বাধা দেয়। এটি "Same-Origin Policy"।
 *
 * CORS হেডার ব্রাউজারকে বলে: "এই backend অন্য origin থেকে request
 * গ্রহণ করতে রাজি আছে।"
 *
 * Preflight Request (OPTIONS) কী?
 * ─────────────────────────────────
 * POST request পাঠানোর আগে ব্রাউজার একটি "অনুমতি চাওয়া" (OPTIONS)
 * request পাঠায়:
 *   ১. ব্রাউজার → সার্ভার: "আমি POST পাঠাতে চাই, JSON body সহ — ঠিক আছে?"
 *   ২. সার্ভার → ব্রাউজার: "হ্যাঁ, ঠিক আছে" (CORS হেডার দিয়ে)
 *   ৩. ব্রাউজার → সার্ভার: আসল POST request পাঠায়
 *
 * OOP ধারণা → Utility Class:
 * ────────────────────────────
 * Utility ক্লাস হলো এমন ক্লাস যেখানে সব method static থাকে।
 * কোনো object তৈরি করার দরকার নেই — সরাসরি ClassName.methodName()
 * কল করা যায়। Java-র Math ক্লাস (Math.abs(), Math.max()) এর একটি
 * পরিচিত উদাহরণ।
 *
 * private constructor → কেউ new CorsUtil() কল করতে পারবে না, কারণ
 * object তৈরি করার কোনো মানে নেই যখন সব method static।
 * ============================================================================
 */

import com.sun.net.httpserver.HttpExchange;  // HTTP request-response জোড়া
import com.sun.net.httpserver.Headers;       // HTTP হেডার collection

import java.io.IOException;                  // IO সংক্রান্ত exception
import java.io.OutputStream;                 // response body লেখার stream

/**
 * CorsUtil — CORS হেডার এবং OPTIONS preflight request পরিচালনা করে।
 *
 * ব্যবহার (অন্যান্য Handler থেকে):
 *   // প্রতিটি handler-এর শুরুতে:
 *   if (CorsUtil.handleCors(exchange)) return;  // OPTIONS হলে এখানেই শেষ
 */
public class CorsUtil {

    // private constructor → Utility ক্লাসের object তৈরি প্রতিরোধ
    private CorsUtil() {}

    // ========================================================================
    // handleCors() — CORS হেডার যোগ করা + OPTIONS request হ্যান্ডেল করা
    // ========================================================================
    /**
     * প্রতিটি API request-এ CORS হেডার যোগ করে।
     * যদি request-টি OPTIONS (preflight) হয়, তাহলে 204 (No Content)
     * response পাঠিয়ে true ফেরত দেয় — বলে দেয় "এটি হ্যান্ডেল হয়ে গেছে,
     * আর কিছু করার দরকার নেই।"
     *
     * কেন boolean ফেরত দেয়?
     * Handler-কে জানাতে হয় OPTIONS request কিনা। যদি হ্যাঁ, handler
     * আর বাকি কোড চালাবে না (return করে দেবে)।
     *
     * @param exchange HttpExchange object — request ও response তথ্য ধারণ করে
     * @return true যদি OPTIONS request হ্যান্ডেল করা হয়ে থাকে, false অন্যথায়
     * @throws IOException response পাঠাতে ব্যর্থ হলে
     */
    public static boolean handleCors(HttpExchange exchange) throws IOException {

        // ====================================================================
        // CORS হেডার সেট করা — PHP-র header() ফাংশনের সমতুল্য
        // ====================================================================
        // getResponseHeaders() → response-এ যোগ করা হেডারগুলোর collection
        // set(key, value)       → হেডার সেট করে (PHP: header("Key: Value"))
        //
        // Headers ক্লাস → java.util.Map-এর মতো কাজ করে (key-value জোড়া)
        // ====================================================================
        Headers headers = exchange.getResponseHeaders();

        /*
         * Access-Control-Allow-Origin: "*"
         * ──────────────────────────────────
         * কোন domain/origin থেকে request আসতে পারবে।
         * "*" মানে যেকোনো domain — ডেভেলপমেন্টের জন্য ঠিক আছে।
         * Production-এ নির্দিষ্ট domain দিতে হবে (যেমন "https://myapp.com")।
         */
        headers.set("Access-Control-Allow-Origin", "*");

        /*
         * Access-Control-Allow-Methods: "GET, POST, OPTIONS"
         * ────────────────────────────────────────────────────
         * কোন HTTP method ব্যবহার করা যাবে।
         * আমাদের API-তে শুধু GET, POST, এবং OPTIONS (preflight) দরকার।
         */
        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");

        /*
         * Access-Control-Allow-Headers: "Content-Type, Authorization"
         * ─────────────────────────────────────────────────────────────
         * frontend কোন custom হেডার পাঠাতে পারবে।
         * Content-Type দরকার কারণ আমরা JSON পাঠাচ্ছি (application/json)।
         * Authorization ভবিষ্যতে JWT token-এর জন্য।
         */
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization");

        /*
         * Access-Control-Allow-Credentials: "true"
         * ──────────────────────────────────────────
         * Cookie বা Authorization header সহ request গ্রহণ করতে রাজি কিনা।
         */
        headers.set("Access-Control-Allow-Credentials", "true");

        // ====================================================================
        // OPTIONS (Preflight) Request হ্যান্ডেল করা
        // ====================================================================
        // যদি request method "OPTIONS" হয়:
        //   ১. HTTP 204 (No Content) response পাঠাই — মানে "ঠিক আছে, পাঠাও"
        //   ২. response body-তে কিছু নেই (-1 মানে no body)
        //   ৩. true ফেরত দিই — handler জানবে এটি হ্যান্ডেল হয়ে গেছে
        //
        // PHP-তে এর সমতুল্য:
        //   if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
        //       http_response_code(200);
        //       exit();
        //   }
        // ====================================================================
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            // 204 No Content — সফল, কিন্তু কোনো response body নেই
            // -1 মানে sendResponseHeaders()-কে বলে যে কোনো body পাঠানো হবে না
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;  // handler-কে বলে: "এই request শেষ, আর কিছু করো না"
        }

        // OPTIONS নয় → false ফেরত দাও, handler তার আসল কাজ করুক
        return false;
    }

    // ========================================================================
    // sendJsonResponse() — JSON Response পাঠানোর সহায়ক Method
    // ========================================================================
    /**
     * HTTP response পাঠায় JSON ফরম্যাটে।
     *
     * কেন আলাদা method?
     * প্রতিটি handler-এ একই ৪-৫ লাইন কোড বারবার লেখা হতো:
     *   headers.set("Content-Type", "application/json");
     *   exchange.sendResponseHeaders(statusCode, bytes.length);
     *   outputStream.write(bytes);
     *   outputStream.close();
     *
     * এই method একবার লিখে সবখানে পুনঃব্যবহার (reuse) করি।
     * OOP ধারণা → Code Reuse (কোড পুনঃব্যবহার):
     * DRY Principle — "Don't Repeat Yourself" — একই কোড বারবার না লিখে
     * একটি method-এ রেখে সবখানে call করো।
     *
     * @param exchange   HttpExchange object
     * @param statusCode HTTP status code (200, 201, 400, 404, 500, ইত্যাদি)
     * @param jsonBody   JSON string যা response body হিসেবে পাঠাতে হবে
     * @throws IOException response পাঠাতে ব্যর্থ হলে
     */
    public static void sendJsonResponse(HttpExchange exchange,
                                         int statusCode,
                                         String jsonBody) throws IOException {

        // Content-Type হেডার সেট করি — ব্রাউজার/frontend জানবে এটি JSON
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

        /*
         * getBytes("UTF-8") কেন?
         * ──────────────────────────
         * HTTP response body-তে byte (বাইট) পাঠাতে হয়, String নয়।
         * "UTF-8" encoding নিশ্চিত করে যে বাংলা ও অন্যান্য Unicode
         * অক্ষর সঠিকভাবে পাঠানো হবে।
         *
         * sendResponseHeaders(statusCode, length):
         *   ১ম প্যারামিটার → HTTP status code
         *   ২য় প্যারামিটার → response body-র আকার (বাইটে)
         *   ব্রাউজার জানে কত বাইট পড়তে হবে — Content-Length হেডার সেট হয়
         */
        byte[] responseBytes = jsonBody.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        /*
         * OutputStream — response body লেখার "পাইপ"
         * ───────────────────────────────────────────────
         * OutputStream হলো Java IO-র একটি abstract class।
         * write(bytes) → বাইট ডেটা ক্লায়েন্টের কাছে পাঠায়
         * close() → stream বন্ধ করে, resource মুক্ত করে
         *
         * PHP-তে এর সমতুল্য: echo json_encode($data);
         * Java-তে আমাদের ম্যানুয়ালি বাইট পাঠাতে হয়।
         */
        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.close();
    }
}
