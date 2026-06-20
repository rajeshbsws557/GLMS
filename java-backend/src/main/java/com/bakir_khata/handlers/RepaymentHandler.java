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

public class RepaymentHandler implements HttpHandler {

    private final Gson gson = new Gson();

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        if (CorsUtil.handleCors(exchange))
            return;

        String method = exchange.getRequestMethod().toUpperCase();

        switch (method) {
            case "POST":
                addRepayment(exchange);
                break;
            case "GET":
                getRepayments(exchange);
                break;
            default:
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message",
                        "Method not allowed. Use POST to add a repayment or GET to view repayments.");
                CorsUtil.sendJsonResponse(exchange, 405, gson.toJson(error));
                break;
        }
    }

    private void addRepayment(HttpExchange exchange) throws IOException {

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

        if (!data.has("loan_id") || data.get("loan_id").isJsonNull() ||
                !data.has("amount_paid") || data.get("amount_paid").isJsonNull()) {

            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Required fields: loan_id, amount_paid.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        int loanId = data.get("loan_id").getAsInt();
        double amountPaid = data.get("amount_paid").getAsDouble();

        if (amountPaid <= 0) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Payment amount must be a positive number.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        Connection conn = null;

        try {
            conn = DatabaseHelper.getInstance().getConnection();

            conn.setAutoCommit(false);

            String sqlCheck = "SELECT loan_id, amount, status FROM loans WHERE loan_id = ? FOR UPDATE";

            PreparedStatement psCheck = conn.prepareStatement(sqlCheck);
            psCheck.setInt(1, loanId);
            ResultSet rsCheck = psCheck.executeQuery();

            if (!rsCheck.next()) {

                conn.rollback();
                rsCheck.close();
                psCheck.close();

                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Loan not found. Cannot add repayment to a non-existent loan.");
                CorsUtil.sendJsonResponse(exchange, 404, gson.toJson(error));
                return;
            }

            String currentStatus = rsCheck.getString("status");
            if ("Settled".equals(currentStatus)) {
                conn.rollback();
                rsCheck.close();
                psCheck.close();

                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "This loan is already fully settled. No further repayments needed.");
                CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
                return;
            }

            double loanAmount = rsCheck.getDouble("amount");
            rsCheck.close();
            psCheck.close();

            String sqlInsert = "INSERT INTO repayments (loan_id, amount_paid) VALUES (?, ?)";

            PreparedStatement psInsert = conn.prepareStatement(sqlInsert,
                    PreparedStatement.RETURN_GENERATED_KEYS);
            psInsert.setInt(1, loanId);
            psInsert.setDouble(2, amountPaid);
            psInsert.executeUpdate();

            ResultSet generatedKeys = psInsert.getGeneratedKeys();
            int repaymentId = 0;
            if (generatedKeys.next()) {
                repaymentId = generatedKeys.getInt(1);
            }
            generatedKeys.close();
            psInsert.close();

            String sqlSum = "SELECT COALESCE(SUM(amount_paid), 0) AS total_paid " +
                    "FROM repayments WHERE loan_id = ?";

            PreparedStatement psSum = conn.prepareStatement(sqlSum);
            psSum.setInt(1, loanId);
            ResultSet rsSum = psSum.executeQuery();
            rsSum.next();

            double totalPaid = rsSum.getDouble("total_paid");
            rsSum.close();
            psSum.close();

            String newStatus;
            if (totalPaid >= loanAmount) {
                newStatus = "Settled";
            } else if (totalPaid > 0) {
                newStatus = "Partially Paid";
            } else {
                newStatus = "Unpaid";
            }

            String sqlUpdate = "UPDATE loans SET status = ? WHERE loan_id = ?";

            PreparedStatement psUpdate = conn.prepareStatement(sqlUpdate);
            psUpdate.setString(1, newStatus);
            psUpdate.setInt(2, loanId);
            psUpdate.executeUpdate();
            psUpdate.close();

            conn.commit();

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Repayment logged successfully!");
            response.addProperty("repayment_id", repaymentId);
            response.addProperty("total_paid", totalPaid);
            response.addProperty("loan_amount", loanAmount);
            response.addProperty("remaining", Math.round((loanAmount - totalPaid) * 100.0) / 100.0);
            response.addProperty("new_status", newStatus);

            CorsUtil.sendJsonResponse(exchange, 201, gson.toJson(response));

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                    System.err.println("⚠️ Transaction rollback successful — all changes discarded.");
                } catch (SQLException rollbackEx) {

                    System.err.println("❌ Rollback also failed! Connection issue: " + rollbackEx.getMessage());
                }
            }

            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Failed to process repayment: " + e.getMessage());
            CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));

        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException autoCommitEx) {
                    System.err.println("⚠️ Error restoring auto-commit: " + autoCommitEx.getMessage());
                }
            }
        }
    }

    private void getRepayments(HttpExchange exchange) throws IOException {

        Map<String, String> params = parseQueryParams(exchange);

        if (!params.containsKey("loan_id")) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Missing required parameter: loan_id");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        int loanId;
        try {
            loanId = Integer.parseInt(params.get("loan_id"));
        } catch (NumberFormatException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "loan_id must be a valid integer.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();

            String sql = "SELECT repayment_id, loan_id, amount_paid, payment_date " +
                    "FROM repayments WHERE loan_id = ? ORDER BY payment_date ASC";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, loanId);

                try (ResultSet rs = ps.executeQuery()) {

                    JsonArray repaymentsArray = new JsonArray();

                    while (rs.next()) {
                        JsonObject repayment = new JsonObject();

                        repayment.addProperty("repayment_id", rs.getInt("repayment_id"));
                        repayment.addProperty("loan_id", rs.getInt("loan_id"));
                        repayment.addProperty("amount_paid", rs.getDouble("amount_paid"));

                        repayment.addProperty("payment_date", rs.getString("payment_date"));

                        repaymentsArray.add(repayment);
                    }

                    JsonObject response = new JsonObject();
                    response.addProperty("success", true);
                    response.add("repayments", repaymentsArray);

                    CorsUtil.sendJsonResponse(exchange, 200, gson.toJson(response));
                }
            }

        } catch (SQLException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Failed to fetch repayments: " + e.getMessage());
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
                if (kv.length == 2)
                    params.put(kv[0], kv[1]);
                else if (kv.length == 1)
                    params.put(kv[0], "");
            }
        }
        return params;
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null)
            sb.append(line);
        reader.close();
        return sb.toString();
    }
}
