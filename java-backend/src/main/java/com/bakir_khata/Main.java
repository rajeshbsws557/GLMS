package com.bakir_khata;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;

import java.io.IOException;

import com.bakir_khata.handlers.AuthHandler;
import com.bakir_khata.handlers.ContactHandler;
import com.bakir_khata.handlers.LoanHandler;
import com.bakir_khata.handlers.RepaymentHandler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.bakir_khata.services.DueLoanNotifier;

public class Main {

    private static final int PORT = 9090;

    public static void main(String[] args) throws IOException {

        try {
            DatabaseHelper.getInstance().getConnection();
            System.out.println("✅ Database connection ready.");
        } catch (Exception e) {
            System.err.println("❌ Database connection failed! Server cannot start.");
            System.err.println("   Reason: " + e.getMessage());
            System.err
                    .println("   Solution: Check if MySQL server is running and personal_ledger database exists.");

            System.exit(1);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/api/auth", new AuthHandler());
        server.createContext("/api/contacts", new ContactHandler());
        server.createContext("/api/loans", new LoanHandler());
        server.createContext("/api/repayments", new RepaymentHandler());

        server.setExecutor(null);

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        scheduler.scheduleAtFixedRate(() -> {
            System.out.println("Starting to check for due loans...");
            DueLoanNotifier.checkAndSendDueNotifications();
        }, 0, 30, TimeUnit.SECONDS); 

        server.start();

        System.out.println("========================================================");
        System.out.println("  🚀 Bakir Khata (GLMS) — Java REST API Server");
        System.out.println("  📡 Port: " + PORT);
        System.out.println("  🌐 Address: http://localhost:" + PORT);
        System.out.println("  📋 Routes:");
        System.out.println("     POST /api/auth       → Registration & Login");
        System.out.println("     GET  /api/contacts   → View Contacts List");
        System.out.println("     POST /api/contacts   → Add New Contact");
        System.out.println("     GET  /api/loans      → View Loans List");
        System.out.println("     POST /api/loans      → Create New Loan");
        System.out.println("     GET  /api/repayments → View Repayment History");
        System.out.println("     POST /api/repayments → Add New Repayment");
        System.out.println("  ⏹️  To stop: Ctrl+C");
        System.out.println("========================================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 Server is shutting down...");
            server.stop(0);
            DatabaseHelper.getInstance().closeConnection();
            System.out.println("✅ Server stopped successfully. Goodbye! 👋");
        }));
    }
}
