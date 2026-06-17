<?php
/**
 * ============================================================================
 * FILE: db.php — Database Connection Handler
 * ============================================================================
 * 
 * PURPOSE:
 * This file establishes a connection to the MySQL database using PHP's PDO
 * (PHP Data Objects) extension. Every other PHP file in the backend includes
 * this file to get access to the database connection.
 * 
 * WHAT IS PDO?
 * PDO stands for "PHP Data Objects." It is a database access layer that
 * provides a UNIFORM interface for accessing different databases (MySQL,
 * PostgreSQL, SQLite, etc.). This means if you ever switch from MySQL to
 * PostgreSQL, you only need to change the connection string (DSN) — not
 * all your query code.
 * 
 * WHY PDO OVER MySQLi?
 * 1. PDO supports 12+ databases; MySQLi only supports MySQL.
 * 2. PDO supports named placeholders (:name) in prepared statements,
 *    making complex queries more readable.
 * 3. PDO is considered the modern, recommended approach in PHP 8+.
 * 
 * KEY CONCEPTS FOR VIVA:
 * - DSN (Data Source Name): A string that tells PDO which database driver
 *   to use and where the database is located.
 * - Prepared Statements: SQL queries where user input is passed separately
 *   from the query structure. This PREVENTS SQL injection attacks because
 *   the database treats input as DATA, never as executable SQL code.
 * - ERRMODE_EXCEPTION: Makes PDO throw exceptions on errors instead of
 *   silently failing. This makes debugging much easier.
 * 
 * ============================================================================
 */

// ============================================================================
// CORS HEADERS — Cross-Origin Resource Sharing
// ============================================================================
// 
// WHAT IS CORS?
// When your frontend (on Vercel, e.g., https://your-app.vercel.app) makes
// an HTTP request to your backend (on Azure, e.g., https://your-vps.com),
// the browser blocks this by default due to the "Same-Origin Policy" — a
// security feature that prevents malicious websites from reading data from
// other sites.
//
// CORS headers tell the browser: "It's okay, I ALLOW requests from this
// other domain." Without these headers, every fetch() call from the frontend
// would fail with a CORS error.
//
// HEADER EXPLANATIONS:
// - Access-Control-Allow-Origin: "*" → Allow requests from ANY domain.
//   In production, you'd replace "*" with your exact Vercel URL for security.
// - Access-Control-Allow-Methods → Which HTTP methods are permitted.
// - Access-Control-Allow-Headers → Which custom headers the frontend can send.
//   "Content-Type" is needed because we send JSON data.
// - Access-Control-Allow-Credentials → Allows cookies/auth headers (future use).
// ============================================================================
header("Access-Control-Allow-Origin: *");
header("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS");
header("Access-Control-Allow-Headers: Content-Type, Authorization");
header("Access-Control-Allow-Credentials: true");

// ============================================================================
// HANDLE PREFLIGHT (OPTIONS) REQUESTS
// ============================================================================
// 
// WHAT IS A PREFLIGHT REQUEST?
// Before sending certain requests (POST with JSON body, custom headers, etc.),
// the browser first sends an "OPTIONS" request to check if the server allows
// the actual request. This is called a "preflight" check.
//
// If we don't handle this, the browser will never send the actual POST/PUT
// request, and your frontend will appear broken.
//
// We respond with HTTP 200 and exit immediately — no database work needed
// for preflight requests.
// ============================================================================
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit();
}

// ============================================================================
// DATABASE CONFIGURATION
// ============================================================================
// These variables store the credentials needed to connect to the MySQL
// database. In a production environment, these would be stored in
// environment variables (e.g., $_ENV['DB_HOST']) for security. For this
// educational demo, they are hardcoded for simplicity.
//
// IMPORTANT: Replace these values with your actual Azure MySQL credentials
// before deploying.
// ============================================================================
$db_host = 'localhost';           // The hostname of the MySQL server
$db_name = 'personal_ledger';    // The name of our database (from schema.sql)
$db_user = 'root';               // MySQL username
$db_pass = '';                    // MySQL password (empty for local dev)
$db_port = '3306';               // MySQL default port

// ============================================================================
// DSN (DATA SOURCE NAME) CONSTRUCTION
// ============================================================================
// The DSN is a string that tells PDO:
//   1. Which database DRIVER to use → "mysql"
//   2. WHERE the database is → "host=..." 
//   3. WHICH database to connect to → "dbname=..."
//   4. Which PORT to use → "port=..."
//   5. Character encoding → "charset=utf8mb4"
//
// WHY utf8mb4?
// `utf8mb4` is MySQL's true UTF-8 encoding that supports ALL Unicode
// characters, including emojis (😀) and special symbols. The older `utf8`
// in MySQL only supports 3-byte characters, missing many modern characters.
// Always use `utf8mb4` for new projects.
// ============================================================================
$dsn = "mysql:host={$db_host};port={$db_port};dbname={$db_name};charset=utf8mb4";

// ============================================================================
// PDO CONNECTION OPTIONS
// ============================================================================
// These options configure HOW PDO behaves. Each one is explained:
//
// 1. ATTR_ERRMODE => ERRMODE_EXCEPTION:
//    Makes PDO throw a PDOException when a query fails, instead of returning
//    false silently. This is ESSENTIAL for debugging — without it, you'd never
//    know why your queries fail.
//
// 2. ATTR_DEFAULT_FETCH_MODE => FETCH_ASSOC:
//    When fetching results, return associative arrays (column name => value)
//    instead of both numeric and associative indexes. This means you write
//    $row['full_name'] instead of $row[0], making code much more readable.
//
// 3. ATTR_EMULATE_PREPARES => false:
//    Disables PDO's "emulated" prepared statements and uses the database's
//    NATIVE prepared statements instead. This is more secure because the
//    database server itself handles parameter binding, providing an extra
//    layer of SQL injection protection.
// ============================================================================
$options = [
    PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
    PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    PDO::ATTR_EMULATE_PREPARES   => false,
];

// ============================================================================
// ESTABLISH THE DATABASE CONNECTION
// ============================================================================
// We use a try-catch block because the connection might fail (wrong password,
// database doesn't exist, server is down, etc.). If it fails, we catch the
// PDOException and return a JSON error message with HTTP status 500 (Internal
// Server Error).
//
// THE $pdo VARIABLE:
// After this block, the variable `$pdo` holds the active database connection.
// Every other PHP file includes this file and uses $pdo to run queries.
// Think of $pdo as the "telephone line" between PHP and MySQL — all
// communication goes through it.
// ============================================================================
try {
    /**
     * Create a new PDO instance (i.e., open a connection to the database).
     * 
     * Parameters:
     *   1. $dsn     → WHERE to connect (driver + host + database)
     *   2. $db_user → WHO is connecting (username)
     *   3. $db_pass → Authentication (password)
     *   4. $options → HOW PDO should behave (error handling, fetch mode, etc.)
     */
    $pdo = new PDO($dsn, $db_user, $db_pass, $options);

} catch (PDOException $e) {
    /**
     * If the connection fails, we:
     * 1. Set the HTTP response code to 500 (Internal Server Error)
     * 2. Set the Content-Type to JSON so the frontend can parse the error
     * 3. Return a JSON object with the error message
     * 4. exit() to stop execution — no point continuing without a database
     * 
     * SECURITY NOTE: In production, you should NOT expose the actual error
     * message ($e->getMessage()) to the client, as it might reveal sensitive
     * information like the database host or username. You'd log it server-side
     * and return a generic error. For this educational demo, we include it
     * for easier debugging.
     */
    http_response_code(500);
    header('Content-Type: application/json');
    echo json_encode([
        'success' => false,
        'message' => 'Database connection failed: ' . $e->getMessage()
    ]);
    exit();
}

/**
 * ============================================================================
 * CONNECTION SUCCESSFUL!
 * ============================================================================
 * At this point, $pdo is a live PDO connection object. Any file that includes
 * db.php (using `require_once 'db.php';`) can now use $pdo to execute queries.
 * 
 * Example usage in another file:
 *   require_once 'db.php';
 *   $stmt = $pdo->prepare("SELECT * FROM users WHERE email = :email");
 *   $stmt->execute([':email' => $userEmail]);
 *   $user = $stmt->fetch();
 * 
 * WHAT HAPPENS WHEN THE SCRIPT ENDS?
 * PHP automatically closes the PDO connection when the script finishes
 * execution. You don't need to call $pdo->close() or anything similar.
 * However, you CAN explicitly close it by setting $pdo = null.
 * ============================================================================
 */
