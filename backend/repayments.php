<?php
/**
 * ============================================================================
 * FILE: repayments.php — Repayment Handler with Auto Status Update
 * ============================================================================
 * 
 * PURPOSE:
 * This is the MOST CRITICAL file in the backend. It handles logging a 
 * repayment against a loan AND automatically updates the loan's status
 * based on how much has been repaid in total.
 * 
 * THE CORE BUSINESS LOGIC:
 * When a user logs a repayment (e.g., "John paid me $200"):
 *   1. INSERT the repayment record into the `repayments` table
 *   2. CALCULATE the total amount repaid for that loan (SUM of all repayments)
 *   3. COMPARE the total against the original loan amount
 *   4. UPDATE the loan status:
 *      - If total_paid >= loan amount → status = 'Settled'
 *      - If total_paid > 0 but < amount → status = 'Partially Paid'
 *      - If total_paid = 0 → status = 'Unpaid' (edge case: repayment deleted)
 * 
 * ALL FOUR STEPS ARE WRAPPED IN A DATABASE TRANSACTION.
 * 
 * WHY TRANSACTIONS? (KEY VIVA CONCEPT)
 * ──────────────────────────────────────────────────────────────────────────
 * A transaction groups multiple SQL statements into a single ATOMIC unit.
 * "Atomic" means: either ALL statements succeed, or NONE of them do.
 * 
 * Without a transaction, imagine this scenario:
 *   Step 1: INSERT repayment → SUCCESS ✓
 *   Step 2: Server crashes before UPDATE
 *   Result: The repayment exists but the loan status is wrong! The data is
 *           now INCONSISTENT — the repayment total doesn't match the status.
 * 
 * With a transaction:
 *   Step 1: INSERT repayment → SUCCESS ✓
 *   Step 2: Server crashes before UPDATE
 *   Result: Transaction is automatically ROLLED BACK. The INSERT is UNDONE.
 *           The database is back to its original, consistent state.
 * 
 * ACID PROPERTIES (explain these in viva):
 *   A - Atomicity:   All-or-nothing execution
 *   C - Consistency: Database moves from one valid state to another
 *   I - Isolation:   Concurrent transactions don't interfere with each other
 *   D - Durability:  Once committed, changes survive even if the server crashes
 * ──────────────────────────────────────────────────────────────────────────
 * 
 * HTTP METHODS:
 *   POST /repayments.php → Log a repayment and auto-update loan status
 *   GET  /repayments.php?loan_id=X → Get all repayments for a specific loan
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
    case 'POST':
        addRepayment($pdo);
        break;
    case 'GET':
        getRepayments($pdo);
        break;
    default:
        http_response_code(405);
        echo json_encode([
            'success' => false,
            'message' => 'Method not allowed. Use POST to add a repayment or GET to view repayments.'
        ]);
        break;
}


// ============================================================================
// FUNCTION: addRepayment
// ============================================================================
/**
 * Logs a repayment and automatically updates the parent loan's status.
 * 
 * EXPECTED JSON BODY:
 * {
 *   "loan_id": 5,         // Required: which loan this repayment is for
 *   "amount_paid": 200.00  // Required: how much was paid
 * }
 * 
 * WORKFLOW (TRANSACTIONAL):
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  BEGIN TRANSACTION                                                   │
 * │  ┌───────────────────────────────────────────────────────────────┐  │
 * │  │ 1. Validate the loan exists and get its original amount       │  │
 * │  │ 2. INSERT the new repayment record                            │  │
 * │  │ 3. SELECT SUM(amount_paid) → total repaid for this loan       │  │
 * │  │ 4. Compare total vs loan amount                               │  │
 * │  │ 5. UPDATE loans.status to the appropriate value               │  │
 * │  └───────────────────────────────────────────────────────────────┘  │
 * │  COMMIT (if all steps succeed)                                      │
 * │  ── or ──                                                           │
 * │  ROLLBACK (if any step fails)                                       │
 * └──────────────────────────────────────────────────────────────────────┘
 * 
 * @param PDO $pdo The database connection object
 */
function addRepayment(PDO $pdo): void
{
    // Read and parse the JSON request body
    $data = json_decode(file_get_contents('php://input'), true);

    // ========================================================================
    // INPUT VALIDATION
    // ========================================================================
    if (!isset($data['loan_id']) || !isset($data['amount_paid'])) {
        http_response_code(400);
        echo json_encode([
            'success' => false,
            'message' => 'Required fields: loan_id, amount_paid.'
        ]);
        return;
    }

    $loan_id     = (int) $data['loan_id'];
    $amount_paid = (float) $data['amount_paid'];

    // Validate the payment amount is positive
    if ($amount_paid <= 0) {
        http_response_code(400);
        echo json_encode([
            'success' => false,
            'message' => 'Payment amount must be a positive number.'
        ]);
        return;
    }

    // ========================================================================
    // BEGIN DATABASE TRANSACTION
    // ========================================================================
    // $pdo->beginTransaction() tells MySQL: "Start grouping the following
    // SQL statements. Don't make any changes permanent until I say COMMIT."
    //
    // If anything goes wrong, we call $pdo->rollBack() which UNDOES all
    // changes made since beginTransaction() was called.
    // ========================================================================
    try {
        $pdo->beginTransaction();

        // ====================================================================
        // STEP 1: Verify the loan exists and get its amount
        // ====================================================================
        // Before adding a repayment, we need to:
        //   a) Confirm the loan_id is valid (exists in the database)
        //   b) Get the original loan amount for comparison later
        //   c) Check if the loan isn't already settled
        //
        // SELECT ... FOR UPDATE:
        // This is a PESSIMISTIC LOCK. It tells MySQL: "I'm reading this row
        // and I plan to UPDATE it later in this transaction. Don't let any
        // other transaction modify this row until I'm done."
        // This prevents race conditions where two simultaneous repayments
        // could both read the same status and cause an incorrect update.
        // ====================================================================
        $sql_check = "SELECT loan_id, amount, status 
                      FROM loans 
                      WHERE loan_id = :loan_id 
                      FOR UPDATE";

        $stmt_check = $pdo->prepare($sql_check);
        $stmt_check->execute([':loan_id' => $loan_id]);
        $loan = $stmt_check->fetch();

        // If no loan was found with this ID, rollback and return error
        if (!$loan) {
            $pdo->rollBack();
            http_response_code(404); // 404 = Not Found
            echo json_encode([
                'success' => false,
                'message' => 'Loan not found. Cannot add repayment to a non-existent loan.'
            ]);
            return;
        }

        // Check if the loan is already settled
        if ($loan['status'] === 'Settled') {
            $pdo->rollBack();
            http_response_code(400);
            echo json_encode([
                'success' => false,
                'message' => 'This loan is already fully settled. No further repayments needed.'
            ]);
            return;
        }

        $loan_amount = (float) $loan['amount'];

        // ====================================================================
        // STEP 2: INSERT the repayment record
        // ====================================================================
        // We add the repayment to the repayments table. The payment_date
        // is automatically set to the current timestamp by the database
        // (DEFAULT CURRENT_TIMESTAMP in the schema).
        // ====================================================================
        $sql_insert = "INSERT INTO repayments (loan_id, amount_paid) 
                       VALUES (:loan_id, :amount_paid)";

        $stmt_insert = $pdo->prepare($sql_insert);
        $stmt_insert->execute([
            ':loan_id'     => $loan_id,
            ':amount_paid' => $amount_paid
        ]);

        $repayment_id = $pdo->lastInsertId();

        // ====================================================================
        // STEP 3: Calculate TOTAL repaid for this loan
        // ====================================================================
        // SUM(amount_paid) adds up ALL repayment amounts for this loan,
        // INCLUDING the one we just inserted in Step 2.
        //
        // COALESCE handles the edge case where SUM returns NULL (which
        // theoretically shouldn't happen since we just inserted a repayment,
        // but defensive programming is always good practice).
        //
        // WHY SUM AND NOT COUNT?
        //   COUNT() → Counts the NUMBER of repayments (e.g., 3 repayments)
        //   SUM()   → Adds up the VALUES (e.g., $200 + $150 + $100 = $450)
        //   We need SUM because we care about the total AMOUNT, not the
        //   number of payments.
        // ====================================================================
        $sql_sum = "SELECT COALESCE(SUM(amount_paid), 0) AS total_paid 
                    FROM repayments 
                    WHERE loan_id = :loan_id";

        $stmt_sum = $pdo->prepare($sql_sum);
        $stmt_sum->execute([':loan_id' => $loan_id]);
        $result = $stmt_sum->fetch();
        $total_paid = (float) $result['total_paid'];

        // ====================================================================
        // STEP 4: Determine the new loan status
        // ====================================================================
        // COMPARISON LOGIC:
        //   - If total_paid >= loan_amount → 'Settled' (fully repaid)
        //     We use >= instead of == to handle the edge case where someone
        //     accidentally overpays (e.g., loan is $500, they pay $600).
        //   - If total_paid > 0 → 'Partially Paid' (some progress made)
        //   - Otherwise → 'Unpaid' (no repayments — shouldn't happen here
        //     since we just added one, but included for completeness)
        //
        // FLOATING POINT NOTE:
        // We use >= instead of == for comparing money values because floating
        // point arithmetic can have tiny precision errors (e.g., 500.00
        // might be stored as 499.9999999...). In a production app, you'd use
        // PHP's bccomp() function or integer cents (storing $5.00 as 500).
        // ====================================================================
        if ($total_paid >= $loan_amount) {
            $new_status = 'Settled';
        } elseif ($total_paid > 0) {
            $new_status = 'Partially Paid';
        } else {
            $new_status = 'Unpaid';
        }

        // ====================================================================
        // STEP 5: UPDATE the loan's status in the database
        // ====================================================================
        // This is the crucial step that keeps the data CONSISTENT.
        // The loans table now reflects the actual repayment state.
        // ====================================================================
        $sql_update = "UPDATE loans SET status = :status WHERE loan_id = :loan_id";

        $stmt_update = $pdo->prepare($sql_update);
        $stmt_update->execute([
            ':status'  => $new_status,
            ':loan_id' => $loan_id
        ]);

        // ====================================================================
        // COMMIT THE TRANSACTION
        // ====================================================================
        // $pdo->commit() tells MySQL: "Everything went well. Make ALL the
        // changes permanent." After commit, the INSERT and UPDATE are
        // permanently saved to disk and visible to other database connections.
        //
        // If we had NOT called commit(), and the script ended, MySQL would
        // automatically ROLLBACK the entire transaction (implicit rollback).
        // ====================================================================
        $pdo->commit();

        // ====================================================================
        // RETURN SUCCESS RESPONSE
        // ====================================================================
        // We return detailed information so the frontend can update its UI
        // without needing to make another API call to re-fetch the loan data.
        // ====================================================================
        http_response_code(201); // 201 = Created
        echo json_encode([
            'success'       => true,
            'message'       => 'Repayment logged successfully!',
            'repayment_id'  => (int) $repayment_id,
            'total_paid'    => $total_paid,
            'loan_amount'   => $loan_amount,
            'remaining'     => round($loan_amount - $total_paid, 2),
            'new_status'    => $new_status
        ]);

    } catch (PDOException $e) {
        // ====================================================================
        // ROLLBACK ON ERROR
        // ====================================================================
        // If ANY step fails (insert, sum, update), we rollback the entire
        // transaction. This means the repayment INSERT is UNDONE, and the
        // database returns to its state before beginTransaction() was called.
        //
        // This is the "A" in ACID — Atomicity. Either everything succeeds,
        // or nothing changes.
        // ====================================================================
        $pdo->rollBack();

        http_response_code(500);
        echo json_encode([
            'success' => false,
            'message' => 'Failed to process repayment: ' . $e->getMessage()
        ]);
    }
}


// ============================================================================
// FUNCTION: getRepayments
// ============================================================================
/**
 * Retrieves all repayment records for a specific loan.
 * 
 * This is useful for showing a repayment history — e.g., "This $500 loan
 * has been repaid in 3 installments: $200 on Jan 15, $150 on Feb 1, $150
 * on Mar 10."
 * 
 * SQL QUERY:
 *   SELECT * FROM repayments WHERE loan_id = :loan_id ORDER BY payment_date ASC
 *   - Chronological order (oldest first) shows the repayment timeline
 * 
 * @param PDO $pdo The database connection object
 */
function getRepayments(PDO $pdo): void
{
    // Validate the required loan_id parameter
    if (!isset($_GET['loan_id'])) {
        http_response_code(400);
        echo json_encode([
            'success' => false,
            'message' => 'Missing required parameter: loan_id'
        ]);
        return;
    }

    $loan_id = (int) $_GET['loan_id'];

    try {
        $sql = "SELECT repayment_id, loan_id, amount_paid, payment_date 
                FROM repayments 
                WHERE loan_id = :loan_id 
                ORDER BY payment_date ASC";

        $stmt = $pdo->prepare($sql);
        $stmt->execute([':loan_id' => $loan_id]);
        $repayments = $stmt->fetchAll();

        // Type-cast numeric values for proper JSON output
        foreach ($repayments as &$repayment) {
            $repayment['repayment_id'] = (int) $repayment['repayment_id'];
            $repayment['loan_id']      = (int) $repayment['loan_id'];
            $repayment['amount_paid']  = (float) $repayment['amount_paid'];
        }
        unset($repayment);

        http_response_code(200);
        echo json_encode([
            'success'    => true,
            'repayments' => $repayments
        ]);

    } catch (PDOException $e) {
        http_response_code(500);
        echo json_encode([
            'success' => false,
            'message' => 'Failed to fetch repayments: ' . $e->getMessage()
        ]);
    }
}
