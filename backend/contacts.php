<?php
/**
 * ============================================================================
 * FILE: contacts.php — Contact Management Endpoint
 * ============================================================================
 * 
 * PURPOSE:
 * Manages the user's contacts (friends/family they lend to or borrow from).
 * Supports two HTTP methods:
 *   - GET  → Retrieve all contacts for a specific user
 *   - POST → Add a new contact to the user's list
 * 
 * DATABASE TABLE: contacts
 * ┌─────────────┬──────────────┬─────────────────────────────┐
 * │ Column      │ Type         │ Description                 │
 * ├─────────────┼──────────────┼─────────────────────────────┤
 * │ contact_id  │ INT (PK)     │ Auto-generated unique ID    │
 * │ user_id     │ INT (FK)     │ References users.user_id    │
 * │ contact_name│ VARCHAR(100) │ Name of the friend/family   │
 * │ phone_number│ VARCHAR(15)  │ Optional phone number       │
 * └─────────────┴──────────────┴─────────────────────────────┘
 * 
 * KEY CONCEPTS FOR VIVA:
 * - REST-like Routing: Using $_SERVER['REQUEST_METHOD'] to determine what
 *   operation to perform (GET = read, POST = create).
 * - Foreign Key Relationship: Each contact MUST belong to an existing user.
 *   The database enforces this via the FOREIGN KEY constraint.
 * - One-to-Many Relationship: One user has many contacts. We filter contacts
 *   using WHERE user_id = :user_id.
 * 
 * HTTP METHODS:
 *   GET  /contacts.php?user_id=1  → Get all contacts for user 1
 *   POST /contacts.php            → Add a new contact (JSON body)
 * ============================================================================
 */

// Include the database connection (also sets CORS headers)
require_once 'db.php';

// Set response type to JSON for all responses from this endpoint
header('Content-Type: application/json');

// ============================================================================
// REQUEST METHOD ROUTING
// ============================================================================
// $_SERVER['REQUEST_METHOD'] contains the HTTP method used by the client:
//   - "GET" when the frontend uses fetch(url) or fetch(url, {method: 'GET'})
//   - "POST" when the frontend uses fetch(url, {method: 'POST', body: ...})
//
// We use a switch statement to route to the correct handler function.
// This is a simplified version of the "Front Controller" pattern used in
// frameworks like Laravel and Symfony.
// ============================================================================
$method = $_SERVER['REQUEST_METHOD'];

switch ($method) {
    case 'GET':
        getContacts($pdo);
        break;
    case 'POST':
        addContact($pdo);
        break;
    default:
        // If someone sends a PUT, DELETE, or other method, return an error
        http_response_code(405); // 405 = Method Not Allowed
        echo json_encode([
            'success' => false,
            'message' => 'Method not allowed. Use GET or POST.'
        ]);
        break;
}


// ============================================================================
// FUNCTION: getContacts
// ============================================================================
/**
 * Retrieves ALL contacts that belong to a specific user.
 * 
 * HOW IT WORKS:
 * 1. The frontend sends a GET request with the user_id as a query parameter:
 *    fetch('https://server.com/contacts.php?user_id=1')
 * 
 * 2. PHP makes query parameters available via the $_GET superglobal array:
 *    $_GET['user_id'] → "1" (always a string from the URL)
 * 
 * 3. We use a prepared statement to safely query the contacts table,
 *    filtering by user_id.
 * 
 * 4. fetchAll() returns ALL matching rows as an array of associative arrays.
 * 
 * SQL QUERY EXPLANATION:
 *   SELECT * FROM contacts WHERE user_id = :user_id ORDER BY contact_name ASC
 *   - SELECT *        → Get all columns from the contacts table
 *   - WHERE user_id   → Only get contacts belonging to THIS user
 *   - ORDER BY ... ASC → Sort alphabetically by name (A→Z) for consistent display
 * 
 * @param PDO $pdo The database connection object
 */
function getContacts(PDO $pdo): void
{
    // ========================================================================
    // VALIDATE THE QUERY PARAMETER
    // ========================================================================
    // isset() checks if the variable exists AND is not null.
    // We need user_id to know which user's contacts to fetch.
    // ========================================================================
    if (!isset($_GET['user_id'])) {
        http_response_code(400); // 400 = Bad Request
        echo json_encode([
            'success' => false,
            'message' => 'Missing required parameter: user_id'
        ]);
        return;
    }

    // Cast to integer for safety — even though prepared statements protect
    // against SQL injection, it's good practice to ensure the type is correct.
    $user_id = (int) $_GET['user_id'];

    try {
        // ====================================================================
        // PREPARED STATEMENT: Fetch all contacts for this user
        // ====================================================================
        // Step 1: prepare() → Sends the query template to MySQL
        // Step 2: execute() → Sends the actual user_id value
        // Step 3: fetchAll() → Gets ALL matching rows as an array
        //
        // fetchAll() vs fetch():
        //   - fetch()    → Returns ONE row (or false if none)
        //   - fetchAll() → Returns ALL matching rows as an array (or empty [])
        //
        // Because we set PDO::FETCH_ASSOC in db.php, each row is an
        // associative array like: ['contact_id' => 1, 'contact_name' => 'John', ...]
        // ====================================================================
        $sql = "SELECT contact_id, contact_name, phone_number 
                FROM contacts 
                WHERE user_id = :user_id 
                ORDER BY contact_name ASC";

        $stmt = $pdo->prepare($sql);
        $stmt->execute([':user_id' => $user_id]);
        $contacts = $stmt->fetchAll();

        http_response_code(200); // 200 = OK
        echo json_encode([
            'success'  => true,
            'contacts' => $contacts
        ]);

    } catch (PDOException $e) {
        http_response_code(500);
        echo json_encode([
            'success' => false,
            'message' => 'Failed to fetch contacts: ' . $e->getMessage()
        ]);
    }
}


// ============================================================================
// FUNCTION: addContact
// ============================================================================
/**
 * Adds a new contact to the database for a specific user.
 * 
 * HOW IT WORKS:
 * 1. The frontend sends a POST request with a JSON body:
 *    {
 *      "user_id": 1,
 *      "contact_name": "Jane Doe",
 *      "phone_number": "+91-9876543210"  // optional
 *    }
 * 
 * 2. We validate the required fields (user_id, contact_name).
 * 
 * 3. We INSERT the new contact using a prepared statement.
 * 
 * 4. We return the new contact's ID on success.
 * 
 * NOTE ON FOREIGN KEY:
 * If the user_id doesn't exist in the users table, the INSERT will fail
 * with a foreign key constraint violation. The database itself prevents
 * the creation of "orphan" contacts that don't belong to any user.
 * 
 * @param PDO $pdo The database connection object
 */
function addContact(PDO $pdo): void
{
    // ========================================================================
    // READ AND PARSE THE REQUEST BODY
    // ========================================================================
    $data = json_decode(file_get_contents('php://input'), true);

    // ========================================================================
    // INPUT VALIDATION
    // ========================================================================
    // Required: user_id and contact_name
    // Optional: phone_number (can be null or missing)
    //
    // The null coalescing operator (??) provides a default value:
    //   $data['contact_name'] ?? '' 
    //   → Returns $data['contact_name'] if it exists, '' otherwise
    // ========================================================================
    if (empty($data['user_id']) || empty(trim($data['contact_name'] ?? ''))) {
        http_response_code(400);
        echo json_encode([
            'success' => false,
            'message' => 'Required fields: user_id, contact_name.'
        ]);
        return;
    }

    $user_id      = (int) $data['user_id'];
    $contact_name = trim($data['contact_name']);
    // phone_number is optional — use null if not provided
    $phone_number = isset($data['phone_number']) ? trim($data['phone_number']) : null;

    try {
        // ====================================================================
        // PREPARED STATEMENT: Insert the new contact
        // ====================================================================
        // The :placeholders are bound to actual values in the execute() call.
        // This prevents SQL injection even if contact_name contains malicious
        // SQL like: Robert'); DROP TABLE contacts; --
        //
        // With prepared statements, that string is stored as-is in the
        // contact_name column. Without them, it could destroy the table!
        // ====================================================================
        $sql = "INSERT INTO contacts (user_id, contact_name, phone_number)
                VALUES (:user_id, :contact_name, :phone_number)";

        $stmt = $pdo->prepare($sql);
        $stmt->execute([
            ':user_id'      => $user_id,
            ':contact_name' => $contact_name,
            ':phone_number' => $phone_number
        ]);

        // Return the newly created contact's ID
        $new_contact_id = $pdo->lastInsertId();

        http_response_code(201); // 201 = Created
        echo json_encode([
            'success'    => true,
            'message'    => 'Contact added successfully!',
            'contact_id' => (int) $new_contact_id
        ]);

    } catch (PDOException $e) {
        // ====================================================================
        // FOREIGN KEY VIOLATION HANDLING
        // ====================================================================
        // If user_id doesn't exist in the users table, MySQL will throw
        // error code 23000 (integrity constraint violation). We catch this
        // and return a helpful message.
        // ====================================================================
        if ($e->getCode() == 23000) {
            http_response_code(400);
            echo json_encode([
                'success' => false,
                'message' => 'Invalid user_id. The specified user does not exist.'
            ]);
        } else {
            http_response_code(500);
            echo json_encode([
                'success' => false,
                'message' => 'Failed to add contact: ' . $e->getMessage()
            ]);
        }
    }
}
