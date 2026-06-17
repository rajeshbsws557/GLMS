# Bakir Khata — Personal IOU Ledger

A full-stack web application for tracking informal loans between friends and family. Record money you've lent or borrowed, log partial repayments, and see your net balance at a glance — all in a clean, mobile-friendly dashboard.

> **Bakir Khata** (बकीर खाता) loosely translates to a "credit ledger" — the digital equivalent of the classic notebook people use to keep track of who owes whom.

---

## ✨ Features

| Feature | Description |
|---|---|
| **User Authentication** | Register and sign in with email and password (bcrypt-hashed) |
| **Contact Book** | Maintain a list of friends/family you transact with |
| **Loan Tracking** | Record loans as *Lent* (they owe you) or *Borrowed* (you owe them) with date, due date, and notes |
| **Partial Repayments** | Log repayments against any loan; the system auto-calculates the remaining balance |
| **Auto Status Updates** | Loan status transitions automatically: `Unpaid → Partially Paid → Settled` |
| **Dashboard Summary** | See total lent, total borrowed, outstanding amounts, and net balance |
| **Repayment History** | View the full repayment timeline for any individual loan |

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| **Frontend** | HTML, Vanilla JS, Tailwind CSS (CDN), Custom CSS |
| **Backend** | PHP 8+ (REST API) |
| **Database** | MySQL 8+ with PDO |
| **Auth** | Bcrypt password hashing via `password_hash()` / `password_verify()` |

---

## 📁 Project Structure

```
GLMS/
├── frontend/
│   ├── index.html          # Login & registration page
│   ├── dashboard.html      # Main dashboard (loans, repayments, stats)
│   ├── css/
│   │   └── custom.css      # Green + white theme, form controls, modals
│   └── js/
│       ├── config.js       # Centralised API base URL
│       ├── auth.js         # Login/register form handlers
│       └── app.js          # Dashboard logic — CRUD for loans & repayments
│
├── backend/
│   ├── db.php              # PDO connection + CORS headers
│   ├── auth.php            # POST — register / login
│   ├── contacts.php        # GET / POST — manage contacts
│   ├── loans.php           # GET / POST — manage loans (with JOIN + subquery)
│   └── repayments.php      # GET / POST — log repayments (transactional)
│
├── schema.sql              # Full database schema (CREATE DATABASE + 4 tables)
└── README.md
```

---

## 🗄️ Database Design

Four tables with foreign-key relationships and cascading deletes:

```
users (1) ──── (N) contacts    → A user has many contacts
users (1) ──── (N) loans       → A user has many loans
contacts (1) ── (N) loans      → A contact can be linked to many loans
loans (1) ──── (N) repayments  → A loan can have many partial repayments
```

Key design decisions:
- **`DECIMAL(10,2)`** for monetary values (avoids floating-point rounding errors)
- **`ENUM`** columns for `loan_type` and `status` (database-level validation)
- **`ON DELETE CASCADE`** to prevent orphan records
- **Database transactions** in `repayments.php` ensure atomic insert + status update

---

## 🚀 Getting Started

### Prerequisites

- **PHP 8.0+** with PDO MySQL extension
- **MySQL 8.0+** (or MariaDB 10.5+)
- A local web server — [XAMPP](https://www.apachefriends.org/), [WAMP](https://www.wampserver.com/), or [Laragon](https://laragon.org/)

### Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/rajeshbsws557/GLMS.git
   cd GLMS
   ```

2. **Create the database**
   ```bash
   mysql -u root -p < schema.sql
   ```
   This creates the `personal_ledger` database with all four tables.

3. **Configure the backend**

   Edit `backend/db.php` and update the credentials if your MySQL setup differs from the defaults:
   ```php
   $db_host = 'localhost';
   $db_name = 'personal_ledger';
   $db_user = 'root';
   $db_pass = '';           // your MySQL password
   $db_port = '3306';
   ```

4. **Configure the frontend**

   Edit `frontend/js/config.js` and point it to your backend:
   ```js
   const API_BASE_URL = 'http://localhost/GLMS/backend/';
   ```

5. **Start your web server** and open `http://localhost/GLMS/frontend/` in a browser.

---

## 📡 API Reference

All endpoints return JSON. The backend is a simple REST-style PHP API.

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/auth.php` | Register (`action: "register"`) or Login (`action: "login"`) |
| `GET` | `/contacts.php?user_id=1` | List all contacts for a user |
| `POST` | `/contacts.php` | Add a new contact |
| `GET` | `/loans.php?user_id=1` | Get all loans (with contact names and repayment totals) |
| `POST` | `/loans.php` | Create a new loan |
| `GET` | `/repayments.php?loan_id=5` | Get repayment history for a loan |
| `POST` | `/repayments.php` | Log a repayment (auto-updates loan status via transaction) |

---

## 🔑 Key DBMS Concepts Demonstrated

This project was built as a DBMS mini-project and showcases the following concepts:

- **Prepared Statements** — All queries use named placeholders (`:param`) via PDO to prevent SQL injection
- **JOINs** — `INNER JOIN` between `loans` and `contacts` to fetch contact names alongside loan data
- **Aggregate Functions** — `SUM()` with `COALESCE()` to compute total repayments
- **Correlated Subqueries** — Per-loan repayment totals calculated inside the main SELECT
- **Transactions (ACID)** — Repayment logging wraps INSERT + SUM + UPDATE in `beginTransaction()` / `commit()` / `rollBack()`
- **Foreign Keys & Cascading Deletes** — Referential integrity enforced at the schema level
- **ENUM Types** — Database-level validation for `loan_type` and `status` columns
- **Password Hashing** — Bcrypt via `password_hash()` with automatic salting

---

## 📄 License

This project is open-source and available under the [MIT License](LICENSE).
