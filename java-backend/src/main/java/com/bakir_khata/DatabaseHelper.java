package com.bakir_khata;

/*
 * ============================================================================
 * ফাইল: DatabaseHelper.java — ডাটাবেস সংযোগ ব্যবস্থাপক (Singleton Pattern)
 * ============================================================================
 *
 * উদ্দেশ্য:
 * এই ক্লাসটি MySQL ডাটাবেসের সাথে JDBC (Java Database Connectivity) ব্যবহার
 * করে সংযোগ (Connection) স্থাপন এবং পরিচালনা করে। এটি PHP-র db.php ফাইলের
 * সমতুল্য — পুরো অ্যাপ্লিকেশনের জন্য একটি কেন্দ্রীয় ডাটাবেস সংযোগ বিন্দু।
 *
 * ============================================================================
 * OOP ধারণা: Singleton Design Pattern (সিঙ্গলটন ডিজাইন প্যাটার্ন)
 * ============================================================================
 * Singleton Pattern নিশ্চিত করে যে একটি ক্লাসের শুধুমাত্র একটি instance
 * (অবজেক্ট) থাকবে এবং সেটি globally accessible হবে।
 *
 * কেন Singleton ব্যবহার করছি?
 * ডাটাবেস কানেকশন তৈরি করা একটি "ব্যয়বহুল" (expensive) অপারেশন —
 * প্রতিটি কানেকশনে TCP হ্যান্ডশেক, authentication, এবং মেমোরি বরাদ্দ হয়।
 * যদি প্রতিটি API request-এ নতুন কানেকশন তৈরি করি, তাহলে:
 *   ১. সার্ভার ধীর হয়ে যাবে
 *   ২. MySQL-এর max_connections সীমা পার হয়ে যেতে পারে
 *   ৩. মেমোরি নষ্ট হবে
 *
 * Singleton Pattern-এর ৩টি মূল উপাদান:
 *   ১. private constructor → বাইরে থেকে new DatabaseHelper() কল করা যাবে না
 *   ২. private static instance → শুধু একটি instance ভেরিয়েবল
 *   ৩. public static getInstance() → সবাই এই method দিয়ে instance পায়
 *
 * ============================================================================
 * JDBC কী? (Java Database Connectivity)
 * ============================================================================
 * JDBC হলো Java-র একটি standard API যা ডাটাবেসের সাথে যোগাযোগ করতে দেয়।
 * এটি java.sql প্যাকেজে থাকে এবং মূলত কিছু Interface প্রদান করে:
 *
 *   ┌──────────────────────────────────────────────────────────────────┐
 *   │  Interface         │  কাজ                                       │
 *   ├──────────────────────────────────────────────────────────────────┤
 *   │  Connection        │  ডাটাবেসের সাথে সংযোগ (টেলিফোন লাইন)       │
 *   │  Statement         │  SQL query পাঠানো (সাধারণ)                  │
 *   │  PreparedStatement │  SQL query পাঠানো (প্যারামিটার সহ, নিরাপদ)  │
 *   │  ResultSet         │  query-র ফলাফল পড়া (টেবিলের সারি-কলাম)     │
 *   └──────────────────────────────────────────────────────────────────┘
 *
 * JDBC Architecture (স্তরবিন্যাস):
 *   আমাদের Java কোড
 *       ↓ (java.sql.* interface ব্যবহার করে)
 *   JDBC Driver Manager
 *       ↓ (সঠিক driver নির্বাচন করে)
 *   MySQL Connector/J (JDBC Driver — Implementation)
 *       ↓ (TCP/IP দিয়ে কথা বলে)
 *   MySQL Server
 *
 * PHP-র PDO-র সাথে তুলনা:
 *   PHP: $pdo = new PDO($dsn, $user, $pass);
 *   Java: Connection conn = DriverManager.getConnection(url, user, pass);
 *
 *   PHP: $stmt = $pdo->prepare("SELECT ... WHERE id = :id");
 *   Java: PreparedStatement ps = conn.prepareStatement("SELECT ... WHERE id = ?");
 *
 *   PHP: $stmt->execute([':id' => $id]);
 *   Java: ps.setInt(1, id); ResultSet rs = ps.executeQuery();
 * ============================================================================
 */

// java.sql প্যাকেজ — JDBC-র মূল interface এবং ক্লাস সমূহ ইম্পোর্ট
import java.sql.Connection;          // ডাটাবেস সংযোগের interface
import java.sql.DriverManager;       // সংযোগ স্থাপনকারী factory ক্লাস
import java.sql.SQLException;        // ডাটাবেস সংক্রান্ত exception (ত্রুটি)

/**
 * DatabaseHelper — ডাটাবেস সংযোগের জন্য Singleton ক্লাস।
 *
 * OOP ধারণা → Encapsulation (এনক্যাপসুলেশন):
 * ─────────────────────────────────────────────
 * এই ক্লাসটি ডাটাবেস সংযোগের সকল বিস্তারিত তথ্য (URL, username, password)
 * নিজের ভেতরে লুকিয়ে রাখে। বাইরের কোড শুধু getConnection() কল করে —
 * কীভাবে সংযোগ তৈরি হয়, সেটা জানার দরকার নেই।
 * এটি "Information Hiding" — OOP-র একটি মূল নীতি।
 */
public class DatabaseHelper {

    // ========================================================================
    // ডাটাবেস কনফিগারেশন (Constants)
    // ========================================================================
    // private → শুধু এই ক্লাসের ভেতর থেকে অ্যাক্সেস করা যাবে (Encapsulation)
    // static  → ক্লাসের সাথে সম্পর্কিত, কোনো object তৈরি ছাড়াই ব্যবহারযোগ্য
    // final   → একবার মান দেওয়ার পর আর পরিবর্তন করা যাবে না (constant/ধ্রুবক)
    //
    // JDBC URL ফরম্যাট: jdbc:mysql://host:port/database_name?parameters
    //   - jdbc:mysql://   → JDBC ড্রাইভারকে বলে MySQL ব্যবহার করতে
    //   - localhost:3306  → MySQL সার্ভারের ঠিকানা ও পোর্ট
    //   - personal_ledger → কোন ডাটাবেসে সংযোগ করব (schema.sql-এ তৈরি)
    //   - useSSL=false    → লোকাল ডেভেলপমেন্টে SSL encryption বন্ধ
    //   - serverTimezone=UTC → টাইমজোন সংক্রান্ত ত্রুটি এড়ানো
    //   - allowPublicKeyRetrieval=true → MySQL 8+ এর authentication সমর্থন
    // ========================================================================
    private static final String DB_URL =
            "jdbc:mysql://localhost:3306/personal_ledger" +
            "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";

    private static final String DB_USER = "root";    // MySQL ব্যবহারকারীর নাম
    private static final String DB_PASS = "";        // MySQL পাসওয়ার্ড (লোকাল ডেভ-এ ফাঁকা)

    // ========================================================================
    // Singleton Instance ভেরিয়েবল
    // ========================================================================
    // volatile keyword কেন?
    // মাল্টি-থ্রেডেড পরিবেশে (যেমন HTTP সার্ভার, যেখানে একাধিক request
    // একসাথে আসে), volatile নিশ্চিত করে যে সব thread এই ভেরিয়েবলের
    // সর্বশেষ (latest) মান দেখবে। এটি ছাড়া, একটি thread পুরনো cached
    // মান দেখতে পারে এবং দুটি instance তৈরি হয়ে যেতে পারে।
    // ========================================================================
    private static volatile DatabaseHelper instance;

    // ========================================================================
    // Connection Object — ডাটাবেসের সাথে সক্রিয় সংযোগ
    // ========================================================================
    // Connection হলো java.sql প্যাকেজের একটি interface।
    // এটি একটি "সেশন" (session) — ডাটাবেসের সাথে খোলা যোগাযোগ পথ।
    // এই Connection দিয়ে আমরা SQL query পাঠাতে পারি, transaction
    // শুরু/শেষ করতে পারি, এবং ফলাফল পড়তে পারি।
    //
    // PHP-তে এটি হলো $pdo ভেরিয়েবল — একই ধারণা, ভিন্ন ভাষায়।
    // ========================================================================
    private Connection connection;

    // ========================================================================
    // Private Constructor — Singleton Pattern-এর মূল বৈশিষ্ট্য
    // ========================================================================
    // constructor-টি private হওয়ায় বাইরে থেকে কেউ new DatabaseHelper()
    // কল করতে পারবে না। শুধুমাত্র getInstance() method-এর মাধ্যমে
    // এই ক্লাসের একমাত্র instance পাওয়া যাবে।
    //
    // OOP ধারণা → Access Modifier (অ্যাক্সেস পরিবর্তক):
    //   public    → সবাই অ্যাক্সেস করতে পারে
    //   private   → শুধু এই ক্লাস থেকে অ্যাক্সেস করা যায়
    //   protected → এই ক্লাস ও তার child ক্লাস থেকে অ্যাক্সেস করা যায়
    //   (default) → শুধু একই প্যাকেজ থেকে অ্যাক্সেস করা যায়
    // ========================================================================
    private DatabaseHelper() {
        // constructor-এ কিছু করার দরকার নেই — connect() আলাদাভাবে কল হবে
    }

    // ========================================================================
    // getInstance() — Singleton Instance প্রদানকারী Method
    // ========================================================================
    /**
     * এই method-টি Double-Checked Locking Pattern ব্যবহার করে।
     *
     * কেন Double-Checked?
     * ──────────────────────
     * ধরুন ২টি thread (T1 এবং T2) একই সময়ে getInstance() কল করল:
     *
     * ১. প্রথম চেক (synchronized-এর বাইরে):
     *    - T1 দেখে instance == null → ভেতরে যায়
     *    - T2 ও দেখে instance == null → ভেতরে যায়
     *
     * ২. synchronized block:
     *    - T1 lock পায়, T2 অপেক্ষা করে
     *    - T1 দ্বিতীয়বার চেক করে → null → নতুন instance তৈরি করে
     *    - T1 lock ছেড়ে দেয়
     *
     * ৩. T2 এর পালা:
     *    - T2 lock পায়
     *    - T2 দ্বিতীয়বার চেক করে → null নয়! (T1 ইতিমধ্যে তৈরি করেছে)
     *    - T2 নতুন instance তৈরি করে না, বিদ্যমানটি ব্যবহার করে
     *
     * এভাবে শুধুমাত্র একটি instance তৈরি হয়, এবং পরবর্তী সব কলে
     * synchronized block-এ ঢুকতে হয় না (পারফরম্যান্স ভালো থাকে)।
     *
     * @return DatabaseHelper-এর একমাত্র instance
     */
    public static DatabaseHelper getInstance() {
        // প্রথম চেক — synchronized ছাড়া (দ্রুত পথ)
        if (instance == null) {
            // synchronized → একবারে শুধু একটি thread এই ব্লকে ঢুকতে পারবে
            // DatabaseHelper.class → ক্লাস-স্তরের lock ব্যবহার করা হচ্ছে
            synchronized (DatabaseHelper.class) {
                // দ্বিতীয় চেক — synchronized-এর ভেতরে (নিরাপদ পথ)
                if (instance == null) {
                    instance = new DatabaseHelper();
                }
            }
        }
        return instance;
    }

    // ========================================================================
    // getConnection() — ডাটাবেস Connection প্রদানকারী Method
    // ========================================================================
    /**
     * একটি সক্রিয় (active) ডাটাবেস Connection ফেরত দেয়।
     *
     * JDBC-তে Connection কীভাবে কাজ করে:
     * ──────────────────────────────────────
     * DriverManager.getConnection(url, user, pass) কল করলে:
     *   ১. DriverManager সব registered JDBC driver-এ URL পাঠায়
     *   ২. MySQL Connector/J দেখে URL "jdbc:mysql://" দিয়ে শুরু → নিজে সাড়া দেয়
     *   ৩. Driver TCP/IP দিয়ে MySQL সার্ভারে সংযোগ স্থাপন করে
     *   ৪. Authentication সম্পন্ন হলে Connection object ফেরত আসে
     *   ৫. ব্যর্থ হলে SQLException throw করে
     *
     * PHP-তে এর সমতুল্য:
     *   $pdo = new PDO("mysql:host=localhost;dbname=personal_ledger", "root", "");
     *
     * connection.isValid(2) কী করে?
     * MySQL কানেকশন দীর্ঘ সময় অলস (idle) থাকলে সার্ভার সেটি বন্ধ করে দিতে পারে
     * (MySQL-এর wait_timeout সেটিং অনুযায়ী, সাধারণত ৮ ঘণ্টা)। isValid(2)
     * ২ সেকেন্ডের মধ্যে একটি ছোট "ping" পাঠিয়ে যাচাই করে কানেকশনটি এখনো
     * জীবিত কিনা। মরে গেলে নতুন কানেকশন তৈরি করি।
     *
     * @return Connection — সক্রিয় MySQL সংযোগ
     * @throws SQLException — সংযোগ ব্যর্থ হলে exception throw হয়
     */
    public Connection getConnection() throws SQLException {
        // কানেকশন নেই অথবা মরে গেছে → নতুন কানেকশন তৈরি করি
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            /*
             * DriverManager.getConnection() — JDBC-র সবচেয়ে গুরুত্বপূর্ণ method
             *
             * প্যারামিটার:
             *   ১. DB_URL  → কোথায় সংযোগ করব (JDBC URL)
             *   ২. DB_USER → কে সংযোগ করছে (username)
             *   ৩. DB_PASS → প্রমাণীকরণ (password)
             *
             * ফেরত দেয়: Connection object — এটি দিয়ে SQL চালানো যাবে
             *
             * SQLException কখন throw করে?
             *   - ভুল URL (ডাটাবেস নেই, হোস্ট পাওয়া যাচ্ছে না)
             *   - ভুল username/password
             *   - MySQL সার্ভার বন্ধ
             *   - max_connections সীমা পার হয়ে গেছে
             */
            connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);

            System.out.println("✅ ডাটাবেস সংযোগ সফল! (MySQL — personal_ledger)");
        }
        return connection;
    }

    // ========================================================================
    // closeConnection() — সংযোগ বন্ধ করা (Resource Management)
    // ========================================================================
    /**
     * ডাটাবেস সংযোগ বন্ধ করে।
     *
     * কেন কানেকশন বন্ধ করা জরুরি?
     * ──────────────────────────────
     * প্রতিটি খোলা কানেকশন সার্ভারে মেমোরি ও একটি thread দখল করে রাখে।
     * কানেকশন বন্ধ না করলে:
     *   ১. মেমোরি লিক (Memory Leak) হয়
     *   ২. MySQL-এর connection pool ফুরিয়ে যায়
     *   ৩. নতুন ব্যবহারকারীরা সংযোগ পায় না
     *
     * Java-তে এটিকে "Resource Management" বলে। try-with-resources
     * statement ব্যবহার করলে connection স্বয়ংক্রিয়ভাবে বন্ধ হয়,
     * কিন্তু Singleton-এ আমরা ম্যানুয়ালি পরিচালনা করি।
     */
    public void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("🔒 ডাটাবেস সংযোগ বন্ধ করা হয়েছে।");
            }
        } catch (SQLException e) {
            /*
             * SQLException → ডাটাবেস সংক্রান্ত যেকোনো ত্রুটি
             *
             * e.getMessage() → ত্রুটির বর্ণনা (যেমন "Connection already closed")
             * e.getSQLState() → SQL state কোড (যেমন "08003" = connection closed)
             * e.getErrorCode() → MySQL-এর নিজস্ব error নম্বর
             *
             * এখানে ত্রুটি হলেও আমরা শুধু লগ করি, কারণ সার্ভার বন্ধ হওয়ার
             * সময় কানেকশন ইতিমধ্যে মরে গেলে close() ব্যর্থ হতে পারে —
             * এটি স্বাভাবিক এবং চিন্তার বিষয় নয়।
             */
            System.err.println("⚠️ কানেকশন বন্ধ করতে সমস্যা: " + e.getMessage());
        }
    }
}
