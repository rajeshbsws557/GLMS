<?php
/**
 * ============================================================================
 * FILE: auth.php — Authentication Handler (Register & Login)
 * ============================================================================
 * 
 * PURPOSE:
 * This file handles TWO operations based on the `action` field in the POST
 * request body:
 *   1. action = "register" → Creates a new user account
 *   2. action = "login"    → Authenticates an existing user
 * 
 * WHY ONE FILE FOR BOTH?
 * Combining register and login into one endpoint simplifies the API structure.
 * The frontend sends a JSON payload with an `action` field, and this script
 * routes to the appropriate logic. This is a common pattern in small APIs.
 * 
 * AUTHENTICATION APPROACH:
 * For this educational demo, we use a SIMPLE approach:
 * - On successful login, the server returns the user_id and full_name.
 * - The frontend stores these in localStorage.
 * - All subsequent API requests include the user_id.
 * 
 * NOTE: In a production application, you would use JWT (JSON Web Tokens) or
 * server-side sessions with cookies. Our approach is sufficient for
 * demonstrating DBMS concepts in a university setting.
 * 
 * KEY CONCEPTS FOR VIVA:
 * - password_hash(): PHP's built-in function that hashes passwords using
 *   bcrypt. It automatically generates a random salt (extra random data mixed
 *   into the hash) to protect against rainbow table attacks.
 * - password_verify(): Compares a plaintext password against a bcrypt hash.
 *   It extracts the salt from the hash and re-hashes the input to check
 *   if they match.
 * - Prepared Statements: We use :named_placeholders to safely insert user
 *   input into SQL queries, preventing SQL injection.
 * 
 * HTTP METHODS: POST only
 * ENDPOINT: /auth.php
 * ============================================================================
 */

// ============================================================================
// INCLUDE THE DATABASE CONNECTION
// ============================================================================
// `require_once` includes the db.php file exactly ONCE. If it's already been
// included (e.g., by another require), it won't include it again. This prevents
// "function already defined" errors.
//
// After this line, the $pdo variable (created in db.php) is available for use.
// The CORS headers from db.php are also already sent.
// ============================================================================
require_once 'db.php';

// ============================================================================
// SET RESPONSE CONTENT TYPE
// ============================================================================
// We tell the browser/frontend that our response will be in JSON format.
// This is important because the frontend uses response.json() to parse it.
// Without this header, some browsers might try to interpret the response as
// HTML and display garbled data.
// ============================================================================
header('Content-Type: application/json');

// ============================================================================
// READ AND PARSE THE REQUEST BODY
// ============================================================================
// 
// WHAT IS php://input?
// When the frontend sends a POST request with a JSON body, PHP doesn't
// automatically parse it into $_POST (that only works for form-encoded data).
// Instead, the raw JSON string is available via `php://input`.
//
// STEP-BY-STEP:
// 1. file_get_contents('php://input') → Reads the raw request body as a string
//    Example: '{"action":"login","email":"john@test.com","password":"123"}'
//
// 2. json_decode($raw, true) → Converts the JSON string into a PHP associative
//    array. The `true` parameter is important — without it, json_decode returns
//    a stdClass object instead of an array.
//    Result: ['action' => 'login', 'email' => 'john@test.com', 'password' => '123']
// ============================================================================
$raw_input = file_get_contents('php://input');
$data = json_decode($raw_input, true);

// ============================================================================
// VALIDATE: Ensure we received valid JSON with an 'action' field
// ============================================================================
// If the JSON was malformed (invalid syntax) or missing entirely, json_decode
// returns null. We also need the 'action' field to know what to do.
// ============================================================================
if (!$data || !isset($data['action'])) {
    http_response_code(400); // 400 = Bad Request
    echo json_encode([
        'success' => false,
        'message' => 'Invalid request. Please provide an "action" field ("register" or "login").'
    ]);
    exit();
}

// ============================================================================
// ROUTE TO THE APPROPRIATE HANDLER
// ============================================================================
// Based on the 'action' field, we call either the register or login function.
// This is a simple routing mechanism — a mini "controller" pattern.
// ============================================================================
$action = $data['action'];

if ($action === 'register') {
    handleRegister($pdo, $data);
} elseif ($action === 'login') {
    handleLogin($pdo, $data);
} else {
    // If the action is neither 'register' nor 'login', return an error
    http_response_code(400);
    echo json_encode([
        'success' => false,
        'message' => 'Unknown action. Use "register" or "login".'
    ]);
}


// ============================================================================
// FUNCTION: handleRegister
// ============================================================================
/**
 * Handles user registration.
 * 
 * WORKFLOW:
 * 1. Validate that all required fields (full_name, email, password) are present
 * 2. Hash the password using bcrypt (NEVER store plaintext passwords!)
 * 3. INSERT the new user into the database using a prepared statement
 * 4. Return success with the new user's ID and name
 * 
 * WHY BCRYPT?
 * Bcrypt is a password hashing algorithm designed to be SLOW on purpose.
 * This means even if an attacker gets your database, they can't quickly
 * brute-force millions of password guesses. It also automatically handles
 * salting (adding random data to each hash so identical passwords produce
 * different hashes).
 * 
 * @param PDO   $pdo  The database connection object
 * @param array $data The parsed JSON request body
 */
function handleRegister(PDO $pdo, array $data): void
{
    // ========================================================================
    // INPUT VALIDATION
    // ========================================================================
    // We check that all three required fields exist and are not empty.
    // `empty()` returns true for: null, '', 0, '0', false, [].
    // The `trim()` function removes leading/trailing whitespace so that
    // a name like "   " (just spaces) is correctly identified as empty.
    // ========================================================================
    if (empty(trim($data['full_name'] ?? '')) || 
        empty(trim($data['email'] ?? '')) || 
        empty($data['password'] ?? '')) {
        
        http_response_code(400); // 400 = Bad Request
        echo json_encode([
            'success' => false,
            'message' => 'All fields are required: full_name, email, password.'
        ]);
        return; // Stop execution of this function
    }

    $full_name = trim($data['full_name']);
    $email     = trim($data['email']);
    $password  = $data['password'];

    // ========================================================================
    // PASSWORD HASHING
    // ========================================================================
    // password_hash() takes the plaintext password and returns a bcrypt hash.
    // 
    // PASSWORD_DEFAULT tells PHP to use the best available algorithm (currently
    // bcrypt, but may change in future PHP versions for better security).
    // 
    // Example:
    //   Input:  "mypassword123"
    //   Output: "$2y$10$N9qo8uLOickgx2ZMRZoMye1dXN4xLz3Pq..." (60 chars)
    //
    // The output includes:
    //   - $2y$    → Algorithm identifier (bcrypt)
    //   - 10$     → Cost factor (2^10 = 1024 iterations)
    //   - Next 22 chars → The random salt
    //   - Remaining chars → The actual hash
    //
    // Because the salt is embedded IN the hash, you don't need to store
    // it separately. password_verify() knows how to extract it.
    // ========================================================================
    $password_hash = password_hash($password, PASSWORD_DEFAULT);

    // ========================================================================
    // INSERT THE USER INTO THE DATABASE
    // ========================================================================
    // We use a TRY-CATCH block because the INSERT might fail if the email
    // already exists (UNIQUE constraint violation). Instead of checking
    // beforehand with a SELECT (which has a race condition), we attempt the
    // INSERT and handle the duplicate error gracefully.
    //
    // PREPARED STATEMENT EXPLANATION:
    // 1. $pdo->prepare(SQL) → Sends the query STRUCTURE to MySQL, with
    //    placeholders (:full_name, :email, :password_hash) instead of values.
    //    MySQL compiles and optimizes this query template.
    // 2. $stmt->execute([...]) → Sends the ACTUAL VALUES separately. MySQL
    //    treats them strictly as data, never as SQL commands. Even if someone
    //    enters ' OR 1=1 -- as their name, it's stored as the literal string
    //    "' OR 1=1 --" and doesn't modify the query logic.
    //
    // This two-step process is WHY prepared statements prevent SQL injection.
    // ========================================================================
    try {
        $sql = "INSERT INTO users (full_name, email, password_hash) 
                VALUES (:full_name, :email, :password_hash)";
        
        $stmt = $pdo->prepare($sql);
        
        $stmt->execute([
            ':full_name'     => $full_name,
            ':email'         => $email,
            ':password_hash' => $password_hash
        ]);

        // ====================================================================
        // GET THE NEW USER'S ID
        // ====================================================================
        // lastInsertId() returns the AUTO_INCREMENT value that MySQL assigned
        // to the new row. We return this to the frontend so it can store it
        // for subsequent API requests.
        // ====================================================================
        $new_user_id = $pdo->lastInsertId();

        http_response_code(201); // 201 = Created (new resource was created)
        echo json_encode([
            'success'   => true,
            'message'   => 'Registration successful!',
            'user_id'   => (int) $new_user_id,
            'full_name' => $full_name
        ]);

    } catch (PDOException $e) {
        // ====================================================================
        // HANDLE DUPLICATE EMAIL ERROR
        // ====================================================================
        // MySQL error code 23000 is the "integrity constraint violation" code,
        // which includes UNIQUE constraint violations. If the email already
        // exists, we get this error.
        //
        // We check the error code to give the user a helpful message instead
        // of a generic database error.
        // ====================================================================
        if ($e->getCode() == 23000) {
            http_response_code(409); // 409 = Conflict (resource already exists)
            echo json_encode([
                'success' => false,
                'message' => 'This email is already registered. Please use a different email or login.'
            ]);
        } else {
            // For any other database error, return a generic server error
            http_response_code(500); // 500 = Internal Server Error
            echo json_encode([
                'success' => false,
                'message' => 'Registration failed: ' . $e->getMessage()
            ]);
        }
    }
}


// ============================================================================
// FUNCTION: handleLogin
// ============================================================================
/**
 * Handles user login (authentication).
 * 
 * WORKFLOW:
 * 1. Validate that email and password are provided
 * 2. Query the database for a user with the given email
 * 3. If found, verify the password against the stored hash
 * 4. Return the user's ID and name on success
 * 
 * SECURITY NOTE:
 * We give the SAME error message for "email not found" and "wrong password."
 * This is intentional — if we said "email not found," an attacker could
 * use the login form to check which emails are registered (user enumeration).
 * By giving a vague message, we reveal nothing.
 * 
 * @param PDO   $pdo  The database connection object
 * @param array $data The parsed JSON request body
 */
function handleLogin(PDO $pdo, array $data): void
{
    // ========================================================================
    // INPUT VALIDATION
    // ========================================================================
    if (empty(trim($data['email'] ?? '')) || empty($data['password'] ?? '')) {
        http_response_code(400);
        echo json_encode([
            'success' => false,
            'message' => 'Email and password are required.'
        ]);
        return;
    }

    $email    = trim($data['email']);
    $password = $data['password'];

    // ========================================================================
    // QUERY THE DATABASE FOR THE USER
    // ========================================================================
    // We SELECT the user by email using a prepared statement.
    // The :email placeholder prevents SQL injection.
    //
    // fetch() retrieves a SINGLE row as an associative array, or returns
    // false if no matching row was found.
    // ========================================================================
    try {
        $sql = "SELECT user_id, full_name, email, password_hash 
                FROM users 
                WHERE email = :email";

        $stmt = $pdo->prepare($sql);
        $stmt->execute([':email' => $email]);

        /**
         * fetch() returns:
         *   - An associative array like ['user_id' => 1, 'full_name' => 'John', ...]
         *     if a matching row is found
         *   - false if no row matches the email
         */
        $user = $stmt->fetch();

        // ====================================================================
        // VERIFY THE PASSWORD
        // ====================================================================
        // password_verify() takes:
        //   1. The plaintext password from the login form
        //   2. The bcrypt hash stored in the database
        //
        // It extracts the salt and cost factor from the stored hash, re-hashes
        // the input password with the same parameters, and checks if the
        // results match.
        //
        // We check BOTH conditions:
        //   - $user exists (email was found in the database)
        //   - password_verify() returns true (password matches the hash)
        //
        // If EITHER fails, we return the same generic error message to prevent
        // user enumeration attacks.
        // ====================================================================
        if ($user && password_verify($password, $user['password_hash'])) {
            // Login successful! Return user info.
            http_response_code(200); // 200 = OK
            echo json_encode([
                'success'   => true,
                'message'   => 'Login successful!',
                'user_id'   => (int) $user['user_id'],
                'full_name' => $user['full_name']
            ]);
        } else {
            // Login failed — intentionally vague error message
            http_response_code(401); // 401 = Unauthorized
            echo json_encode([
                'success' => false,
                'message' => 'Invalid email or password.'
            ]);
        }

    } catch (PDOException $e) {
        http_response_code(500);
        echo json_encode([
            'success' => false,
            'message' => 'Login failed due to server error: ' . $e->getMessage()
        ]);
    }
}
