# Bakir Khata — Personal IOU Ledger

A full-stack web application for tracking informal loans between friends and family. Record money you've lent or borrowed, log partial repayments, and see your net balance at a glance — all in a clean, mobile-friendly dashboard.

> **Bakir Khata** (बकीर खाता) loosely translates to a "credit ledger" — the digital equivalent of the classic notebook people use to keep track of who owes whom.

---

## ✨ Features

| Feature | Description |
|---|---|
| **User Authentication** | Register and sign in with email and password (bcrypt-hashed) |
| **Contact Book** | Maintain a list of friends/family you transact with. Supports real-time editing and deletion. |
| **Loan Tracking** | Record loans as *Lent* (they owe you) or *Borrowed* (you owe them) with date, due date, and notes |
| **Partial Repayments** | Log repayments against any loan; the system auto-calculates the remaining balance |
| **Auto Status Updates** | Loan status transitions automatically: `Unpaid → Partially Paid → Settled` |
| **Dashboard Summary** | See total lent, total borrowed, outstanding amounts, and net balance |
| **Repayment History** | View the full repayment timeline for any individual loan |
| **Email Notifications** | Automated HTML email reminders sent for due loans using a background daemon |

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| **Frontend** | HTML, Vanilla JS, Tailwind CSS (CDN), Custom CSS |
| **Backend** | Java 17+ (No frameworks, pure `com.sun.net.httpserver.HttpServer`) |
| **Database** | MySQL 8+ with JDBC (`java.sql.*`) |
| **Auth** | Bcrypt password hashing via `jbcrypt` |
| **Build/Dependencies**| Maven (`mysql-connector-j`, `gson`, `jbcrypt`) |

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
├── java-backend/
│   ├── pom.xml             # Maven configuration and dependencies
│   └── src/main/java/com/bakir_khata/
│       ├── Main.java               # HttpServer setup and routing
│       ├── DatabaseHelper.java     # JDBC connection pooling (Singleton)
│       └── handlers/
│           ├── CorsUtil.java       # CORS headers and JSON responses
│           ├── AuthHandler.java    # POST — register / login
│           ├── ContactHandler.java # GET / POST — manage contacts
│           ├── LoanHandler.java    # GET / POST — manage loans
│           └── RepaymentHandler.java # GET / POST — log repayments (transactional)
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
- **Database transactions** in `RepaymentHandler.java` ensure atomic insert + status update

---

## 🚀 Getting Started

### Prerequisites

- **JDK 17+** (Java Development Kit)
- **Apache Maven 3.x**
- **MySQL 8.0+** (or MariaDB 10.5+)
- A local web server — to serve the frontend (e.g. Live Server VS Code Extension, XAMPP, etc.)

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

   Edit `java-backend/src/main/java/com/bakir_khata/DatabaseHelper.java` and update the credentials if your MySQL setup differs from the defaults:
   ```java
   private static final String URL = "jdbc:mysql://localhost:3306/personal_ledger";
   private static final String USER = "root";
   private static final String PASS = "";
   ```

4. **Run the backend**
   ```bash
   cd java-backend
   mvn clean compile
   mvn exec:java -Dexec.mainClass="com.bakir_khata.Main"
   ```
   The server will start on `http://localhost:8080`.

5. **Configure the frontend**

   Edit `frontend/js/config.js` and point it to your Java backend:
   ```js
   const API_BASE_URL = 'http://localhost:8080/api/';
   ```

6. **Start your frontend server** and open `index.html` in a browser.

---

## 📡 API Reference

All endpoints return JSON. The backend is a simple REST-style API powered by Java's built-in HttpServer.

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/auth` | Register (`action: "register"`) or Login (`action: "login"`) |
| `GET` | `/api/contacts?user_id=1` | List all contacts for a user |
| `POST` | `/api/contacts` | Add a new contact |
| `PUT` | `/api/contacts` | Update an existing contact |
| `DELETE`| `/api/contacts?user_id=1&contact_id=1` | Delete a contact (cascades to loans/repayments) |
| `GET` | `/api/loans?user_id=1` | Get all loans (with contact names and repayment totals) |
| `POST` | `/api/loans` | Create a new loan |
| `DELETE`| `/api/loans?user_id=1&loan_id=1` | Delete an individual loan (cascades to repayments) |
| `GET` | `/api/repayments?loan_id=5` | Get repayment history for a loan |
| `POST` | `/api/repayments` | Log a repayment (auto-updates loan status via transaction) |

---

## 🔑 Key DBMS Concepts Demonstrated

This project was built as a DBMS mini-project and showcases the following concepts:

- **Prepared Statements** — All queries use parameterized queries via JDBC to prevent SQL injection
- **JOINs** — `INNER JOIN` between `loans` and `contacts` to fetch contact names alongside loan data
- **Aggregate Functions** — `SUM()` with `COALESCE()` to compute total repayments
- **Correlated Subqueries** — Per-loan repayment totals calculated inside the main SELECT
- **Transactions (ACID)** — Repayment logging wraps INSERT + SUM + UPDATE in manual JDBC transactions (`conn.setAutoCommit(false)`, `commit()`, `rollback()`)
- **Pessimistic Locking** — Used `FOR UPDATE` clause when fetching the loan record during repayment to prevent race conditions
- **Foreign Keys & Cascading Deletes** — Referential integrity enforced at the schema level
- **ENUM Types** — Database-level validation for `loan_type` and `status` columns
- **Password Hashing** — Bcrypt via `jbcrypt` with automatic salting

---

## 📄 License

This project is open-source and available under the [MIT License](LICENSE).
