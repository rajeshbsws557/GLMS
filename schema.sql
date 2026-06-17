-- ============================================================================
-- PERSONAL IOU LEDGER — DATABASE SCHEMA
-- ============================================================================
-- 
-- PURPOSE:
-- This SQL script creates the entire database schema for the Personal IOU
-- Ledger application. It defines four tables that work together to track
-- informal loans between a user and their friends/family.
--
-- HOW TO RUN:
-- Execute this script in your MySQL client (e.g., MySQL Workbench, phpMyAdmin,
-- or the command line) to set up the database from scratch.
--
-- RELATIONSHIP MAP (Entity-Relationship Summary):
--   users (1) ──── (N) contacts    → A user can have many contacts
--   users (1) ──── (N) loans       → A user can have many loans
--   contacts (1) ── (N) loans      → A contact can be linked to many loans
--   loans (1) ──── (N) repayments  → A loan can have many partial repayments
--
-- KEY CONCEPTS FOR VIVA:
-- 1. PRIMARY KEY: Uniquely identifies each row in a table. Auto-incremented
--    here so the database assigns the next integer automatically.
-- 2. FOREIGN KEY: Creates a link between two tables, enforcing referential
--    integrity (you can't create a loan for a non-existent user).
-- 3. ON DELETE CASCADE: When a parent row is deleted, all child rows that
--    reference it are automatically deleted too. This prevents orphan records.
-- 4. ENUM: A string data type that restricts values to a predefined list.
--    Useful for columns like 'status' where only specific values are valid.
-- ============================================================================


-- ============================================================================
-- STEP 1: CREATE THE DATABASE
-- ============================================================================
-- `CREATE DATABASE IF NOT EXISTS` is a safe command — it only creates the
-- database if it doesn't already exist. This prevents errors when re-running
-- the script on a server that already has the database.
-- ============================================================================
CREATE DATABASE IF NOT EXISTS personal_ledger;

-- `USE` tells MySQL that all subsequent commands in this script should be
-- executed inside the `personal_ledger` database.
USE personal_ledger;


-- ============================================================================
-- TABLE 1: users
-- ============================================================================
-- PURPOSE: Stores the registered users of the application. Each user has a
-- unique account identified by their email address.
--
-- VIVA NOTES:
-- - `INT AUTO_INCREMENT PRIMARY KEY`: The `user_id` column is an integer that
--   automatically increases by 1 for each new user. It serves as the PRIMARY
--   KEY, meaning it uniquely identifies every row in this table.
-- - `VARCHAR(100)`: A variable-length string that can hold UP TO 100
--   characters. Unlike `CHAR(100)` which always uses 100 bytes, VARCHAR only
--   uses as much storage as the actual string length + 1-2 bytes overhead.
--   This is more storage-efficient for names and emails.
-- - `UNIQUE`: The `email` column has a UNIQUE constraint, meaning no two
--   users can register with the same email. The database will reject any
--   INSERT that would create a duplicate.
-- - `NOT NULL`: These columns MUST have a value. The database will reject
--   any INSERT that leaves these columns empty.
-- - `password_hash VARCHAR(255)`: We store a HASHED version of the password,
--   NEVER the plaintext password. The PHP function `password_hash()` produces
--   a 60-character bcrypt string, but we use VARCHAR(255) for future-proofing
--   in case the hashing algorithm changes to produce longer hashes.
-- - `TIMESTAMP DEFAULT CURRENT_TIMESTAMP`: Automatically records the exact
--   date and time when the row was created. Useful for auditing.
-- ============================================================================
CREATE TABLE users (
    user_id INT AUTO_INCREMENT PRIMARY KEY,       -- Unique identifier for each user
    full_name VARCHAR(100) NOT NULL,              -- User's display name
    email VARCHAR(100) UNIQUE NOT NULL,           -- Login credential; must be unique
    password_hash VARCHAR(255) NOT NULL,          -- Bcrypt hash of the user's password
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP -- Auto-set when user registers
);


-- ============================================================================
-- TABLE 2: contacts
-- ============================================================================
-- PURPOSE: Stores the friends/family members that a user lends money to or
-- borrows money from. Each contact belongs to exactly one user.
--
-- VIVA NOTES:
-- - This table has a FOREIGN KEY (`user_id`) that references the `users`
--   table. This establishes a one-to-many relationship: one user can have
--   many contacts.
-- - `ON DELETE CASCADE`: If a user deletes their account (row is removed from
--   `users`), ALL of their contacts are automatically deleted too. Without
--   CASCADE, the database would refuse to delete the user because child
--   rows (contacts) still reference them — this is called a "foreign key
--   constraint violation."
-- - `phone_number VARCHAR(15)`: Optional field (no NOT NULL constraint).
--   VARCHAR(15) accommodates international phone numbers including country
--   codes (e.g., +91-9876543210 = 14 characters).
-- ============================================================================
CREATE TABLE contacts (
    contact_id INT AUTO_INCREMENT PRIMARY KEY,    -- Unique identifier for each contact
    user_id INT NOT NULL,                         -- Which user this contact belongs to
    contact_name VARCHAR(100) NOT NULL,           -- Name of the friend/family member
    phone_number VARCHAR(15),                     -- Optional phone number

    -- FOREIGN KEY CONSTRAINT:
    -- This ensures that `user_id` in this table MUST match an existing
    -- `user_id` in the `users` table. You cannot add a contact for a
    -- user that doesn't exist.
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);


-- ============================================================================
-- TABLE 3: loans
-- ============================================================================
-- PURPOSE: The core table of the application. Each row represents a single
-- loan transaction — either money the user has LENT to a contact, or money
-- the user has BORROWED from a contact.
--
-- VIVA NOTES:
-- - `ENUM('Lent', 'Borrowed')`: Restricts the `loan_type` column to exactly
--   two possible values. If someone tries to INSERT a value like 'Gift', the
--   database will reject it. This is a form of data validation at the
--   database level, which is more reliable than validating only in the
--   application code (defense in depth).
-- - `DECIMAL(10, 2)`: A fixed-point number type perfect for storing monetary
--   values. `10` is the total number of digits, and `2` is the number of
--   digits after the decimal point. This means it can store values from
--   -99,999,999.99 to 99,999,999.99. We use DECIMAL instead of FLOAT
--   because FLOAT can introduce tiny rounding errors (e.g., 10.30 might
--   become 10.2999999...), which is unacceptable for financial data.
-- - `DATE`: Stores only the date (YYYY-MM-DD), no time component. Perfect
--   for loan_date and due_date where time-of-day is irrelevant.
-- - `status ENUM(...)`: Tracks the repayment progress of the loan. The
--   application code (repayments.php) automatically updates this value
--   when repayments are logged:
--     'Unpaid'         → No repayments have been made yet (default)
--     'Partially Paid' → Some repayments made, but total < loan amount
--     'Settled'        → Total repayments >= loan amount (fully repaid)
-- - `TEXT`: Used for `notes` because it can store very long strings
--   (up to 65,535 characters), unlike VARCHAR which maxes at 65,535 but
--   counts toward the row size limit. TEXT is stored separately.
-- - This table has TWO foreign keys — linking it to both `users` and
--   `contacts`. This is a common pattern called a "junction" or
--   "association" pattern when multiple entities relate to each other.
-- ============================================================================
CREATE TABLE loans (
    loan_id INT AUTO_INCREMENT PRIMARY KEY,       -- Unique identifier for each loan
    user_id INT NOT NULL,                         -- The user who created this loan record
    contact_id INT NOT NULL,                      -- The contact involved in this loan
    loan_type ENUM('Lent', 'Borrowed') NOT NULL,  -- Direction of the loan
    amount DECIMAL(10, 2) NOT NULL,               -- Original loan amount
    loan_date DATE NOT NULL,                      -- When the loan was made
    due_date DATE,                                -- Optional: when repayment is expected
    status ENUM('Unpaid', 'Partially Paid', 'Settled') DEFAULT 'Unpaid', -- Auto-updated
    notes TEXT,                                   -- Optional description/reason

    -- FOREIGN KEY to users: ensures the loan belongs to a valid user
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,

    -- FOREIGN KEY to contacts: ensures the loan references a valid contact
    FOREIGN KEY (contact_id) REFERENCES contacts(contact_id) ON DELETE CASCADE
);


-- ============================================================================
-- TABLE 4: repayments
-- ============================================================================
-- PURPOSE: Tracks individual partial (or full) payments made against a loan.
-- A single loan can have MULTIPLE repayments — this is the one-to-many
-- relationship between `loans` and `repayments`.
--
-- VIVA NOTES:
-- - This table enables "partial payment tracking," which is a key feature
--   of the application. Instead of marking a loan as simply "paid" or
--   "unpaid," we record each individual payment with its amount and date.
-- - `TIMESTAMP DEFAULT CURRENT_TIMESTAMP`: Automatically records when the
--   repayment was logged. Unlike the `loan_date` (which is a DATE chosen
--   by the user), this is a system-generated timestamp for accurate
--   record-keeping.
-- - The application logic (in repayments.php) performs this workflow:
--   1. INSERT the new repayment row
--   2. SELECT SUM(amount_paid) for all repayments of this loan
--   3. Compare the sum against the original loan amount
--   4. UPDATE loans.status to 'Partially Paid' or 'Settled'
--   This entire process is wrapped in a DATABASE TRANSACTION to ensure
--   atomicity — either ALL steps succeed, or NONE of them do.
-- ============================================================================
CREATE TABLE repayments (
    repayment_id INT AUTO_INCREMENT PRIMARY KEY,  -- Unique identifier for each repayment
    loan_id INT NOT NULL,                         -- Which loan this repayment is for
    amount_paid DECIMAL(10, 2) NOT NULL,          -- How much was paid in this installment
    payment_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP, -- Auto-recorded timestamp

    -- FOREIGN KEY to loans: ensures the repayment references an existing loan
    FOREIGN KEY (loan_id) REFERENCES loans(loan_id) ON DELETE CASCADE
);


-- ============================================================================
-- END OF SCHEMA
-- ============================================================================
-- After running this script, you should have:
--   1. A database called `personal_ledger`
--   2. Four tables: users, contacts, loans, repayments
--   3. Foreign key relationships enforcing referential integrity
--   4. Cascading deletes to prevent orphan records
--
-- You can verify the tables were created by running:
--   SHOW TABLES;
--   DESCRIBE users;
--   DESCRIBE contacts;
--   DESCRIBE loans;
--   DESCRIBE repayments;
-- ============================================================================
