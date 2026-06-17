<?php
/**
 * ============================================================================
 * FILE: loans.php — Loan Management Endpoint
 * ============================================================================
 * 
 * PURPOSE:
 * Manages the core loan records — the heart of the IOU Ledger application.
 * Each loan represents money that the user has either LENT to a contact or
 * BORROWED from a contact.
 * 
 * Supports two HTTP methods:
 *   - GET  → Retrieve all loans for a user (JOINed with contacts for names)
 *   - POST → Create a new loan record
 * 
 * KEY CONCEPTS FOR VIVA:
 * - SQL JOIN: We use an INNER JOIN to combine data from the `loans` and
 *   `contacts` tables. This way, the frontend gets the contact's NAME
 *   alongside the loan data, instead of just a contact_id number.
 * - Aggregate Subquery: We use a correlated subquery with COALESCE and SUM
 *   to calculate how much has been repaid for each loan.
 * - ENUM Type: loan_type and status columns only accept predefined values,
 *   providing database-level validation.
 * 
 * HTTP METHODS:
 *   GET  /loans.php?user_id=1  → Get all loans for user 1 (with contact names)
 *   POST /loans.php            → Create a new loan (JSON body)
 * ============================================================================
 */

// Include the database connection (also sets CORS headers)
require_once 'db.php';

// Set response type to JSON
header('Content-Type: application/json');

// ============================================================================
// REQUEST METHOD ROUTING
// ============================================================================
$method = $_SERVER['REQUEST_METHOD'];

switch ($method) {
    case 'GET':
        getLoans($pdo);
        break;
    case 'POST':
        addLoan($pdo);
        break;
    default:
        http_response_code(405);
        echo json_encode([
            'success' => false,
            'message' => 'Method not allowed. Use GET or POST.'
        ]);
        break;
}


// ============================================================================
// FUNCTION: getLoans
// ============================================================================
/**
 * Retrieves ALL loans for a specific user, enriched with contact names and
 * repayment totals.
 * 
 * THIS IS THE MOST COMPLEX QUERY IN THE APPLICATION — perfect for viva!
 * 
 * SQL QUERY BREAKDOWN:
 * ──────────────────────────────────────────────────────────────────────────
 * SELECT 
 *     l.*,                              -- All columns from the loans table
 *     c.contact_name,                   -- The contact's name (from JOIN)
 *     COALESCE(                         -- Handle NULL (no repayments yet)
 *         (SELECT SUM(r.amount_paid)    -- Sum ALL repayments for this loan
 *          FROM repayments r            
 *          WHERE r.loan_id = l.loan_id  -- Correlated: links to outer query
 *         ), 0                          -- If NULL (no repayments), use 0
 *     ) AS total_paid                   -- Alias: frontend accesses as 'total_paid'
 * FROM loans l
 *     INNER JOIN contacts c             -- Combine loans with contacts
 *     ON l.contact_id = c.contact_id   -- Matching condition
 * WHERE l.user_id = :user_id           -- Filter by the logged-in user
 * ORDER BY l.loan_date DESC            -- Newest loans first
 * ──────────────────────────────────────────────────────────────────────────
 * 
 * CONCEPTS EXPLAINED:
 * 
 * 1. INNER JOIN:
 *    Combines rows from `loans` and `contacts` where the contact_id matches.
 *    If a loan references contact_id=5, the JOIN finds the row in contacts
 *    where contact_id=5 and adds its columns (contact_name) to the result.
 *    Only rows with matches in BOTH tables are returned (unlike LEFT JOIN).
 * 
 * 2. Correlated Subquery:
 *    The subquery (SELECT SUM(...) FROM repayments WHERE r.loan_id = l.loan_id)
 *    runs ONCE for each row in the outer query. It's "correlated" because it
 *    references the outer query's loan_id (l.loan_id). This calculates the
 *    total amount paid for EACH loan individually.
 * 
 * 3. COALESCE(value, default):
 *    Returns the first non-NULL argument. SUM() returns NULL when there are
 *    no repayment rows for a loan (not 0!). COALESCE converts NULL to 0,
 *    preventing the frontend from displaying "null" or breaking calculations.
 * 
 * 4. Table Aliases (l, c, r):
 *    We use short aliases (l for loans, c for contacts, r for repayments)
 *    to make the query shorter and more readable. Without aliases, we'd
 *    write loans.loan_id instead of l.loan_id.
 * 
 * @param PDO $pdo The database connection object
 */
function getLoans(PDO $pdo): void
{
    // Validate the required user_id parameter
    if (!isset($_GET['user_id'])) {
        http_response_code(400);
        echo json_encode([
            'success' => false,
            'message' => 'Missing required parameter: user_id'
        ]);
        return;
    }

    $user_id = (int) $_GET['user_id'];

    try {
        // ====================================================================
        // THE BIG QUERY: Loans + Contact Names + Total Repaid
        // ====================================================================
        // This single query gets everything the dashboard needs:
        //   - All loan details (amount, dates, status, notes)
        //   - The contact's name (via JOIN)
        //   - How much has been repaid (via subquery)
        //   - The remaining balance can be calculated by the frontend:
        //     remaining = amount - total_paid
        // ====================================================================
        $sql = "SELECT 
                    l.loan_id,
                    l.user_id,
                    l.contact_id,
                    l.loan_type,
                    l.amount,
                    l.loan_date,
                    l.due_date,
                    l.status,
                    l.notes,
                    c.contact_name,
                    COALESCE(
                        (SELECT SUM(r.amount_paid) 
                         FROM repayments r 
                         WHERE r.loan_id = l.loan_id
                        ), 0
                    ) AS total_paid
                FROM loans l
                    INNER JOIN contacts c ON l.contact_id = c.contact_id
                WHERE l.user_id = :user_id
                ORDER BY 
                    CASE l.status 
                        WHEN 'Unpaid' THEN 1 
                        WHEN 'Partially Paid' THEN 2 
                        WHEN 'Settled' THEN 3 
                    END,
                    l.loan_date DESC";

        /**
         * ORDER BY CASE ... END:
         * This is a custom sort order using a CASE expression. Instead of
         * sorting alphabetically (which would put "Partially Paid" before
         * "Settled" and "Unpaid"), we define our own priority:
         *   1 = Unpaid (show first — needs attention)
         *   2 = Partially Paid (show second — in progress)
         *   3 = Settled (show last — completed)
         * Within each status group, loans are sorted by date (newest first).
         */

        $stmt = $pdo->prepare($sql);
        $stmt->execute([':user_id' => $user_id]);
        $loans = $stmt->fetchAll();

        // ====================================================================
        // TYPE CASTING FOR FRONTEND COMPATIBILITY
        // ====================================================================
        // PDO returns all values as STRINGS by default (even numbers).
        // The frontend JavaScript needs proper types for calculations, so
        // we cast numeric fields to their correct PHP types. json_encode()
        // will then output them as JSON numbers instead of JSON strings.
        //
        // Without this: {"amount": "500.00", "total_paid": "200.00"}
        // With this:    {"amount": 500.00, "total_paid": 200.00}
        // ====================================================================
        foreach ($loans as &$loan) {
            $loan['loan_id']    = (int) $loan['loan_id'];
            $loan['contact_id'] = (int) $loan['contact_id'];
            $loan['amount']     = (float) $loan['amount'];
            $loan['total_paid'] = (float) $loan['total_paid'];
        }
        // unset the reference to prevent accidental modification later
        unset($loan);

        http_response_code(200);
        echo json_encode([
            'success' => true,
            'loans'   => $loans
        ]);

    } catch (PDOException $e) {
        http_response_code(500);
        echo json_encode([
            'success' => false,
            'message' => 'Failed to fetch loans: ' . $e->getMessage()
        ]);
    }
}


// ============================================================================
// FUNCTION: addLoan
// ============================================================================
/**
 * Creates a new loan record in the database.
 * 
 * EXPECTED JSON BODY:
 * {
 *   "user_id": 1,           // Required: who is creating this loan
 *   "contact_id": 3,        // Required: who is the loan with
 *   "loan_type": "Lent",    // Required: "Lent" or "Borrowed"
 *   "amount": 500.00,       // Required: the loan amount
 *   "loan_date": "2024-01-15", // Required: when the loan was made
 *   "due_date": "2024-03-15",  // Optional: when repayment is expected
 *   "notes": "For textbooks"   // Optional: description
 * }
 * 
 * VALIDATION:
 * - user_id, contact_id, loan_type, amount, and loan_date are required
 * - loan_type must be either 'Lent' or 'Borrowed' (database ENUM enforces this)
 * - amount must be a positive number
 * - The new loan starts with status = 'Unpaid' (database default)
 * 
 * @param PDO $pdo The database connection object
 */
function addLoan(PDO $pdo): void
{
    // Read and parse the JSON request body
    $data = json_decode(file_get_contents('php://input'), true);

    // ========================================================================
    // INPUT VALIDATION
    // ========================================================================
    // We validate all required fields. The amount must be positive (you can't
    // lend or borrow $0 or negative money).
    //
    // NOTE: We validate loan_type in PHP as well, even though the database's
    // ENUM constraint would catch invalid values. This is "defense in depth" —
    // validating at multiple layers provides better error messages and catches
    // issues earlier.
    // ========================================================================
    $required_fields = ['user_id', 'contact_id', 'loan_type', 'amount', 'loan_date'];
    foreach ($required_fields as $field) {
        if (!isset($data[$field]) || (is_string($data[$field]) && trim($data[$field]) === '')) {
            http_response_code(400);
            echo json_encode([
                'success' => false,
                'message' => "Missing required field: {$field}"
            ]);
            return;
        }
    }

    // Validate loan_type is one of the allowed ENUM values
    $valid_types = ['Lent', 'Borrowed'];
    if (!in_array($data['loan_type'], $valid_types, true)) {
        http_response_code(400);
        echo json_encode([
            'success' => false,
            'message' => 'loan_type must be either "Lent" or "Borrowed".'
        ]);
        return;
    }

    // Validate amount is a positive number
    $amount = (float) $data['amount'];
    if ($amount <= 0) {
        http_response_code(400);
        echo json_encode([
            'success' => false,
            'message' => 'Amount must be a positive number.'
        ]);
        return;
    }

    // Extract and sanitize all fields
    $user_id    = (int) $data['user_id'];
    $contact_id = (int) $data['contact_id'];
    $loan_type  = $data['loan_type'];
    $loan_date  = $data['loan_date'];
    $due_date   = isset($data['due_date']) && !empty($data['due_date']) ? $data['due_date'] : null;
    $notes      = isset($data['notes']) ? trim($data['notes']) : null;

    try {
        // ====================================================================
        // PREPARED STATEMENT: Insert the new loan
        // ====================================================================
        // Note: We do NOT include 'status' in the INSERT because the database
        // default value ('Unpaid') will be used automatically. This is a good
        // practice — let the database handle defaults rather than hardcoding
        // them in application code. If the default ever changes in the schema,
        // you only update it in ONE place (the database).
        // ====================================================================
        $sql = "INSERT INTO loans (user_id, contact_id, loan_type, amount, loan_date, due_date, notes)
                VALUES (:user_id, :contact_id, :loan_type, :amount, :loan_date, :due_date, :notes)";

        $stmt = $pdo->prepare($sql);
        $stmt->execute([
            ':user_id'    => $user_id,
            ':contact_id' => $contact_id,
            ':loan_type'  => $loan_type,
            ':amount'     => $amount,
            ':loan_date'  => $loan_date,
            ':due_date'   => $due_date,
            ':notes'      => $notes
        ]);

        $new_loan_id = $pdo->lastInsertId();

        http_response_code(201); // 201 = Created
        echo json_encode([
            'success' => true,
            'message' => 'Loan created successfully!',
            'loan_id' => (int) $new_loan_id
        ]);

    } catch (PDOException $e) {
        // Handle foreign key violations (invalid user_id or contact_id)
        if ($e->getCode() == 23000) {
            http_response_code(400);
            echo json_encode([
                'success' => false,
                'message' => 'Invalid user_id or contact_id. Referenced records must exist.'
            ]);
        } else {
            http_response_code(500);
            echo json_encode([
                'success' => false,
                'message' => 'Failed to create loan: ' . $e->getMessage()
            ]);
        }
    }
}
