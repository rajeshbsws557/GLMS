package com.bakir_khata;

import java.sql.Connection;          
import java.sql.DriverManager;       
import java.sql.SQLException;        

public class DatabaseHelper {

    private static final String DB_URL =
            "jdbc:mysql://localhost:3306/personal_ledger" +
            "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";

    private static final String DB_USER = "root";    
    private static final String DB_PASS = "";        

    private static volatile DatabaseHelper instance;

    private Connection connection;

    private DatabaseHelper() {

    }

    public static DatabaseHelper getInstance() {

        if (instance == null) {

            synchronized (DatabaseHelper.class) {

                if (instance == null) {
                    instance = new DatabaseHelper();
                }
            }
        }
        return instance;
    }

    public Connection getConnection() throws SQLException {

        if (connection == null || connection.isClosed() || !connection.isValid(2)) {

            connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);

            System.out.println("✅ Database connection successful! (MySQL — personal_ledger)");
        }
        return connection;
    }

    public void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("🔒 Database connection closed.");
            }
        } catch (SQLException e) {

            System.err.println("⚠️ Error closing connection: " + e.getMessage());
        }
    }
}
