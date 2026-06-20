CREATE DATABASE IF NOT EXISTS personal_ledger;

USE personal_ledger;

CREATE TABLE users (
    user_id INT AUTO_INCREMENT PRIMARY KEY,       
    full_name VARCHAR(100) NOT NULL,              
    email VARCHAR(100) UNIQUE NOT NULL,           
    password_hash VARCHAR(255) NOT NULL,          
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP 
);

CREATE TABLE contacts (
    contact_id INT AUTO_INCREMENT PRIMARY KEY,    
    user_id INT NOT NULL,                         
    contact_name VARCHAR(100) NOT NULL,           
    email VARCHAR(100),                           
    phone_number VARCHAR(15),                     

    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE TABLE loans (
    loan_id INT AUTO_INCREMENT PRIMARY KEY,       
    user_id INT NOT NULL,                         
    contact_id INT NOT NULL,                      
    loan_type ENUM('Lent', 'Borrowed') NOT NULL,  
    amount DECIMAL(10, 2) NOT NULL,               
    loan_date DATE NOT NULL,                      
    due_date DATE,                                
    status ENUM('Unpaid', 'Partially Paid', 'Settled') DEFAULT 'Unpaid', 
    notes TEXT,                                   

    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,

    FOREIGN KEY (contact_id) REFERENCES contacts(contact_id) ON DELETE CASCADE
);

CREATE TABLE repayments (
    repayment_id INT AUTO_INCREMENT PRIMARY KEY,  
    loan_id INT NOT NULL,                         
    amount_paid DECIMAL(10, 2) NOT NULL,          
    payment_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP, 

    FOREIGN KEY (loan_id) REFERENCES loans(loan_id) ON DELETE CASCADE
);

