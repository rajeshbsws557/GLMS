package com.bakir_khata;

/*
 * ============================================================================
 * ফাইল: Main.java — অ্যাপ্লিকেশনের Entry Point (প্রবেশ বিন্দু)
 * ============================================================================
 *
 * উদ্দেশ্য:
 * এটি পুরো অ্যাপ্লিকেশনের "শুরুর দরজা"। এই ক্লাস:
 *   ১. একটি HTTP সার্ভার তৈরি করে (পোর্ট 8080-এ)
 *   ২. API route (পথ) সংজ্ঞায়িত করে (/api/auth, /api/contacts, ইত্যাদি)
 *   ৩. প্রতিটি route-এ সংশ্লিষ্ট Handler ক্লাস নিযুক্ত করে
 *   ৪. সার্ভার বন্ধ হলে ডাটাবেস কানেকশন বন্ধ করে (Shutdown Hook)
 *
 * ============================================================================
 * com.sun.net.httpserver.HttpServer কী?
 * ============================================================================
 * Java JDK-র সাথে একটি সাধারণ (lightweight) HTTP সার্ভার আসে।
 * এটি কোনো বাহ্যিক framework (Spring Boot, Jakarta EE) ছাড়াই একটি
 * REST API তৈরি করতে দেয়।
 *
 * PHP-র সাথে তুলনা:
 *   PHP-তে Apache/Nginx সার্ভার PHP ফাইলগুলো serve করে।
 *   Java-তে আমরা নিজেরাই সার্ভার তৈরি করি এবং route ম্যানেজ করি।
 *
 * HttpServer-এর মূল উপাদান:
 *   ┌────────────────────────────────────────────────────────────────┐
 *   │  HttpServer      │  সার্ভার নিজে — পোর্ট শুনে, request গ্রহণ করে  │
 *   │  HttpContext      │  একটি URL path + তার Handler-এর জোড়া        │
 *   │  HttpHandler      │  একটি interface — request এলে কী করব         │
 *   │  HttpExchange     │  একটি request-response জোড়া (PHP-র $_GET,    │
 *   │                   │  $_POST, echo এর সমতুল্য)                    │
 *   └────────────────────────────────────────────────────────────────┘
 *
 * ============================================================================
 * কেন কোনো Framework ব্যবহার করছি না?
 * ============================================================================
 * শিক্ষাগত উদ্দেশ্যে — ফ্রেমওয়ার্ক অনেক কিছু "পর্দার আড়ালে" করে দেয়।
 * Vanilla Java ব্যবহার করে আমরা বুঝতে পারি:
 *   - HTTP request/response কীভাবে কাজ করে
 *   - CORS কী এবং কেন দরকার
 *   - JSON parse/serialize কীভাবে হয়
 *   - JDBC সরাসরি কীভাবে কাজ করে
 * ============================================================================
 */

// Java-র বিল্ট-ইন HTTP সার্ভার ক্লাস ইম্পোর্ট
import com.sun.net.httpserver.HttpServer;

// Java-র নেটওয়ার্ক সংক্রান্ত ক্লাস — সার্ভারের ঠিকানা ও পোর্ট সেট করতে
import java.net.InetSocketAddress;

// IO Exception — নেটওয়ার্ক/ফাইল সংক্রান্ত ত্রুটি ধরতে
import java.io.IOException;

// Handler ক্লাসগুলো ইম্পোর্ট — প্রতিটি API endpoint-এর জন্য একটি
import com.bakir_khata.handlers.AuthHandler;
import com.bakir_khata.handlers.ContactHandler;
import com.bakir_khata.handlers.LoanHandler;
import com.bakir_khata.handlers.RepaymentHandler;

/**
 * Main ক্লাস — অ্যাপ্লিকেশনের প্রবেশ বিন্দু।
 *
 * OOP ধারণা → Class (ক্লাস):
 * ──────────────────────────────
 * Java-তে সবকিছু একটি ক্লাসের ভেতরে থাকে। ক্লাস হলো একটি "ছাঁচ" (blueprint)
 * যা থেকে object তৈরি হয়। Main ক্লাস বিশেষ — এর main() method থেকে
 * প্রোগ্রাম চালু হয়।
 */
public class Main {

    // ========================================================================
    // সার্ভার কনফিগারেশন — ধ্রুবক (Constant)
    // ========================================================================
    // এই পোর্টে সার্ভার শুনবে। Frontend-এর config.js-এ
    // API_BASE_URL = "http://localhost:8080" সেট করতে হবে।
    //
    // কেন 8080 এবং 80 নয়?
    // পোর্ট 80 হলো HTTP-র ডিফল্ট পোর্ট, কিন্তু Linux/Mac-এ 1024-এর নিচের
    // পোর্ট ব্যবহার করতে root/admin অনুমতি লাগে। 8080 conventionally
    // ডেভেলপমেন্ট সার্ভারের জন্য ব্যবহৃত হয়।
    // ========================================================================
    private static final int PORT = 9090;

    // ========================================================================
    // main() Method — প্রোগ্রামের শুরু
    // ========================================================================
    /**
     * JVM (Java Virtual Machine) এই method-টি সবার আগে কল করে।
     *
     * public → JVM বাইরে থেকে কল করতে পারে
     * static → কোনো object তৈরি ছাড়াই কল করা যায়
     * void → কিছু ফেরত দেয় না
     * String[] args → কমান্ড লাইন আর্গুমেন্ট (যেমন: java Main --port 9090)
     *
     * @param args কমান্ড লাইন আর্গুমেন্ট (এই প্রোগ্রামে ব্যবহৃত হয় না)
     * @throws IOException সার্ভার শুরু করতে ব্যর্থ হলে
     */
    public static void main(String[] args) throws IOException {

        // ====================================================================
        // ধাপ ১: ডাটাবেস সংযোগ পরীক্ষা
        // ====================================================================
        // সার্ভার শুরুর আগেই ডাটাবেস সংযোগ যাচাই করি।
        // ডাটাবেস ছাড়া API কাজ করবে না, তাই ব্যর্থ হলে সার্ভার শুরু না
        // করাই ভালো — "fail fast" নীতি।
        // ====================================================================
        try {
            DatabaseHelper.getInstance().getConnection();
            System.out.println("✅ Database connection ready.");
        } catch (Exception e) {
            System.err.println("❌ Database connection failed! Server cannot start.");
            System.err.println("   Reason: " + e.getMessage());
            System.err
                    .println("   Solution: Check if MySQL server is running and personal_ledger database exists.");
            // System.exit(1) → প্রোগ্রাম থেকে বের হয়ে যায়
            // exit code 1 → ত্রুটি সহ বের হওয়া (0 = সফল)
            System.exit(1);
        }

        // ====================================================================
        // ধাপ ২: HTTP সার্ভার তৈরি
        // ====================================================================
        // HttpServer.create() একটি static factory method।
        //
        // OOP ধারণা → Factory Method Pattern:
        // constructor (new HttpServer()) ব্যবহার না করে static method
        // ব্যবহার করা হচ্ছে। এটি ক্লাসের ভেতরের implementation বিস্তারিত
        // লুকিয়ে রাখে এবং ভবিষ্যতে বিভিন্ন ধরনের সার্ভার ফেরত দিতে পারে।
        //
        // InetSocketAddress → IP ঠিকানা + পোর্ট নম্বরের জোড়া
        // new InetSocketAddress(8080) → "0.0.0.0:8080" — সব IP-তে শুনবে
        //
        // দ্বিতীয় প্যারামিটার (0) → backlog — একসাথে কতটি pending
        // connection রাখবে। 0 মানে সিস্টেম ডিফল্ট ব্যবহার করো।
        // ====================================================================
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // ====================================================================
        // ধাপ ৩: API Routes (পথ) নিবন্ধন
        // ====================================================================
        // createContext(path, handler) → এই path-এ request এলে এই handler কল হবে
        //
        // এটি PHP-র mod_rewrite বা .htaccess-এর সমতুল্য:
        // PHP: RewriteRule ^api/auth$ auth.php
        // Java: server.createContext("/api/auth", new AuthHandler())
        //
        // প্রতিটি Handler হলো HttpHandler interface-এর implementation।
        //
        // OOP ধারণা → Interface (ইন্টারফেস):
        // HttpHandler হলো একটি interface যেখানে একটি method আছে:
        // void handle(HttpExchange exchange)
        // প্রতিটি Handler ক্লাস এই interface implement করে নিজের মতো
        // handle() method লেখে। এটি হলো "Polymorphism" — একই method
        // বিভিন্ন ক্লাসে বিভিন্নভাবে কাজ করে।
        //
        // Route ম্যাপিং:
        // /api/auth → AuthHandler (রেজিস্ট্রেশন ও লগইন)
        // /api/contacts → ContactHandler (যোগাযোগ তালিকা)
        // /api/loans → LoanHandler (ঋণ ব্যবস্থাপনা)
        // /api/repayments → RepaymentHandler (পরিশোধ ব্যবস্থাপনা)
        // ====================================================================
        server.createContext("/api/auth", new AuthHandler());
        server.createContext("/api/contacts", new ContactHandler());
        server.createContext("/api/loans", new LoanHandler());
        server.createContext("/api/repayments", new RepaymentHandler());

        // ====================================================================
        // ধাপ ৪: Executor সেট করা
        // ====================================================================
        // Executor হলো একটি কৌশল যা নির্ধারণ করে কীভাবে request-গুলো
        // process হবে (কোন thread-এ চলবে)।
        //
        // null সেট করলে Java ডিফল্ট threading ব্যবহার করে — প্রতিটি
        // request-এর জন্য একটি নতুন thread তৈরি হয়। ছোট প্রজেক্টের
        // জন্য এটি যথেষ্ট।
        //
        // বড় প্রজেক্টে ExecutorService ব্যবহার করা হয় thread pool-এর
        // জন্য, যাতে thread তৈরি/ধ্বংসের overhead কমে।
        // ====================================================================
        server.setExecutor(null);

        // ====================================================================
        // ধাপ ৫: সার্ভার শুরু!
        // ====================================================================
        server.start();

        System.out.println("========================================================");
        System.out.println("  🚀 Bakir Khata (GLMS) — Java REST API Server");
        System.out.println("  📡 Port: " + PORT);
        System.out.println("  🌐 Address: http://localhost:" + PORT);
        System.out.println("  📋 Routes:");
        System.out.println("     POST /api/auth       → Registration & Login");
        System.out.println("     GET  /api/contacts   → View Contacts List");
        System.out.println("     POST /api/contacts   → Add New Contact");
        System.out.println("     GET  /api/loans      → View Loans List");
        System.out.println("     POST /api/loans      → Create New Loan");
        System.out.println("     GET  /api/repayments → View Repayment History");
        System.out.println("     POST /api/repayments → Add New Repayment");
        System.out.println("  ⏹️  To stop: Ctrl+C");
        System.out.println("========================================================");

        // ====================================================================
        // ধাপ ৬: Shutdown Hook — সার্ভার বন্ধের সময় পরিষ্কার-পরিচ্ছন্নতা
        // ====================================================================
        // Shutdown Hook হলো একটি thread যা JVM বন্ধ হওয়ার ঠিক আগে চলে।
        // Ctrl+C চাপলে বা সিস্টেম বন্ধ হলে এই কোড execute হয়।
        //
        // এখানে আমরা:
        // ১. HTTP সার্ভার বন্ধ করি (0 = কোনো delay ছাড়া)
        // ২. ডাটাবেস কানেকশন বন্ধ করি (resource মুক্ত করা)
        //
        // কেন এটি গুরুত্বপূর্ণ?
        // কানেকশন বন্ধ না করলে MySQL-এ "orphan connection" থেকে যায়,
        // যা সার্ভারের resource নষ্ট করে। এটি "graceful shutdown" —
        // সভ্যভাবে বিদায় নেওয়া।
        //
        // OOP ধারণা → Anonymous Class (বেনামী ক্লাস):
        // new Thread() { ... } → Thread ক্লাসের একটি child ক্লাস inline
        // তৈরি করা হচ্ছে যার run() method override করা হয়েছে।
        // এটি Inheritance (উত্তরাধিকার) ও Polymorphism-এর উদাহরণ।
        // ====================================================================
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 Server is shutting down...");
            server.stop(0);
            DatabaseHelper.getInstance().closeConnection();
            System.out.println("✅ Server stopped successfully. Goodbye! 👋");
        }));
    }
}
