package com.bakir_khata.handlers;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;

import com.bakir_khata.DatabaseHelper;

public class LoanHandler implements HttpHandler {

    private final Gson gson = new Gson();

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        if (CorsUtil.handleCors(exchange)) return;

        String method = exchange.getRequestMethod().toUpperCase();

        switch (method) {
            case "GET":
                getLoans(exchange);
                break;
            case "POST":
                addLoan(exchange);
                break;
            case "DELETE":
                deleteLoan(exchange);
                break;
            default:
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Method not allowed. Use GET, POST, or DELETE.");
                CorsUtil.sendJsonResponse(exchange, 405, gson.toJson(error));
                break;
        }
    }

    private void getLoans(HttpExchange exchange) throws IOException {

        Map<String, String> params = parseQueryParams(exchange);

        if (!params.containsKey("user_id")) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Missing required parameter: user_id");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        int userId;
        try {
            userId = Integer.parseInt(params.get("user_id"));
        } catch (NumberFormatException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "user_id must be a valid integer.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();

            String sql = "SELECT " +
                    "l.loan_id, l.user_id, l.contact_id, l.loan_type, " +
                    "l.amount, l.loan_date, l.due_date, l.status, l.notes, " +
                    "c.contact_name, " +
                    "COALESCE(" +
                    "    (SELECT SUM(r.amount_paid) FROM repayments r WHERE r.loan_id = l.loan_id), 0" +
                    ") AS total_paid " +
                    "FROM loans l " +
                    "INNER JOIN contacts c ON l.contact_id = c.contact_id " +
                    "WHERE l.user_id = ? " +
                    "ORDER BY " +
                    "CASE l.status " +
                    "    WHEN 'Unpaid' THEN 1 " +
                    "    WHEN 'Partially Paid' THEN 2 " +
                    "    WHEN 'Settled' THEN 3 " +
                    "END, " +
                    "l.loan_date DESC";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);

                try (ResultSet rs = ps.executeQuery()) {

                    JsonArray loansArray = new JsonArray();

                    while (rs.next()) {
                        JsonObject loan = new JsonObject();

                        loan.addProperty("loan_id", rs.getInt("loan_id"));
                        loan.addProperty("user_id", rs.getInt("user_id"));
                        loan.addProperty("contact_id", rs.getInt("contact_id"));

                        loan.addProperty("loan_type", rs.getString("loan_type"));
                        loan.addProperty("status", rs.getString("status"));
                        loan.addProperty("contact_name", rs.getString("contact_name"));

                        loan.addProperty("amount", rs.getDouble("amount"));
                        loan.addProperty("total_paid", rs.getDouble("total_paid"));

                        loan.addProperty("loan_date", rs.getString("loan_date"));

                        String dueDate = rs.getString("due_date");
                        if (dueDate != null) {
                            loan.addProperty("due_date", dueDate);
                        } else {
                            loan.add("due_date", null);
                        }

                        String notes = rs.getString("notes");
                        if (notes != null) {
                            loan.addProperty("notes", notes);
                        } else {
                            loan.add("notes", null);
                        }

                        loansArray.add(loan);
                    }

                    JsonObject response = new JsonObject();
                    response.addProperty("success", true);
                    response.add("loans", loansArray);

                    CorsUtil.sendJsonResponse(exchange, 200, gson.toJson(response));
                }
            }

        } catch (SQLException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Failed to fetch loans: " + e.getMessage());
            CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
        }
    }

    private void addLoan(HttpExchange exchange) throws IOException {

        String requestBody = readRequestBody(exchange);
        JsonObject data;

        try {
            data = JsonParser.parseString(requestBody).getAsJsonObject();
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Invalid JSON in request body.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        String[] requiredFields = {"user_id", "contact_id", "loan_type", "amount", "loan_date"};

        for (String field : requiredFields) {
            if (!data.has(field) || data.get(field).isJsonNull()) {
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Missing required field: " + field);
                CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
                return;
            }

            if (data.get(field).isJsonPrimitive() && data.get(field).getAsJsonPrimitive().isString()) {
                if (data.get(field).getAsString().trim().isEmpty()) {
                    JsonObject error = new JsonObject();
                    error.addProperty("success", false);
                    error.addProperty("message", "Missing required field: " + field);
                    CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
                    return;
                }
            }
        }

        String loanType = data.get("loan_type").getAsString();
        if (!"Lent".equals(loanType) && !"Borrowed".equals(loanType)) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "loan_type must be either \"Lent\" or \"Borrowed\".");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        double amount = data.get("amount").getAsDouble();
        if (amount <= 0) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Amount must be a positive number.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        int userId = data.get("user_id").getAsInt();
        int contactId = data.get("contact_id").getAsInt();
        String loanDate = data.get("loan_date").getAsString();

        String dueDate = null;
        if (data.has("due_date") && !data.get("due_date").isJsonNull()) {
            String dd = data.get("due_date").getAsString().trim();
            if (!dd.isEmpty()) dueDate = dd;
        }

        String notes = null;
        if (data.has("notes") && !data.get("notes").isJsonNull()) {
            notes = data.get("notes").getAsString().trim();
            if (notes.isEmpty()) notes = null;
        }

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();

            String sql = "INSERT INTO loans (user_id, contact_id, loan_type, amount, loan_date, due_date, notes) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement ps = conn.prepareStatement(sql,
                    PreparedStatement.RETURN_GENERATED_KEYS)) {

                ps.setInt(1, userId);
                ps.setInt(2, contactId);
                ps.setString(3, loanType);
                ps.setDouble(4, amount);
                ps.setString(5, loanDate);

                if (dueDate != null) {
                    ps.setString(6, dueDate);
                } else {
                    ps.setNull(6, java.sql.Types.DATE);
                }

                if (notes != null) {
                    ps.setString(7, notes);
                } else {
                    ps.setNull(7, java.sql.Types.VARCHAR);
                }

                ps.executeUpdate();

                ResultSet generatedKeys = ps.getGeneratedKeys();
                int newLoanId = 0;
                if (generatedKeys.next()) {
                    newLoanId = generatedKeys.getInt(1);
                }

                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("message", "Loan created successfully!");
                response.addProperty("loan_id", newLoanId);

                CorsUtil.sendJsonResponse(exchange, 201, gson.toJson(response));
            }

        } catch (SQLException e) {

            if ("23000".equals(e.getSQLState())) {
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Invalid user_id or contact_id. Referenced records must exist.");
                CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            } else {
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Failed to create loan: " + e.getMessage());
                CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
            }
        }
    }

    private void deleteLoan(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQueryParams(exchange);

        if (!params.containsKey("user_id") || !params.containsKey("loan_id")) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Missing required parameters: user_id, loan_id");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        int userId, loanId;
        try {
            userId = Integer.parseInt(params.get("user_id"));
            loanId = Integer.parseInt(params.get("loan_id"));
        } catch (NumberFormatException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "user_id and loan_id must be valid integers.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();
            String sql = "DELETE FROM loans WHERE loan_id = ? AND user_id = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, loanId);
                ps.setInt(2, userId);

                int rowsAffected = ps.executeUpdate();

                if (rowsAffected > 0) {
                    JsonObject response = new JsonObject();
                    response.addProperty("success", true);
                    response.addProperty("message", "Loan deleted successfully!");
                    CorsUtil.sendJsonResponse(exchange, 200, gson.toJson(response));
                } else {
                    JsonObject error = new JsonObject();
                    error.addProperty("success", false);
                    error.addProperty("message", "Loan not found or you do not have permission to delete it.");
                    CorsUtil.sendJsonResponse(exchange, 404, gson.toJson(error));
                }
            }
        } catch (SQLException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Failed to delete loan: " + e.getMessage());
            CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
        }
    }

    private Map<String, String> parseQueryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getQuery();
        if (query != null && !query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) params.put(kv[0], kv[1]);
                else if (kv.length == 1) params.put(kv[0], "");
            }
        }
        return params;
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }
}
