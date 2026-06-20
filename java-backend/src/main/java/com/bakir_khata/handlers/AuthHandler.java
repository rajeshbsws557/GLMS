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

import com.google.gson.Gson;                  
import com.google.gson.JsonObject;            
import com.google.gson.JsonParser;            

import org.mindrot.jbcrypt.BCrypt;            

import com.bakir_khata.DatabaseHelper;        

public class AuthHandler implements HttpHandler {

    private final Gson gson = new Gson();

    @Override  
    public void handle(HttpExchange exchange) throws IOException {

        if (CorsUtil.handleCors(exchange)) return;

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Method not allowed. Use POST.");
            CorsUtil.sendJsonResponse(exchange, 405, gson.toJson(error));
            return;
        }

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

        if (!data.has("action") || data.get("action").isJsonNull()) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Invalid request. Please provide an \"action\" field (\"register\" or \"login\").");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        String action = data.get("action").getAsString();

        switch (action) {
            case "register":
                handleRegister(exchange, data);
                break;
            case "login":
                handleLogin(exchange, data);
                break;
            default:
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Unknown action. Use \"register\" or \"login\".");
                CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
                break;
        }
    }

    private void handleRegister(HttpExchange exchange, JsonObject data) throws IOException {

        String fullName = getStringField(data, "full_name");
        String email = getStringField(data, "email");
        String password = getStringField(data, "password");

        if (fullName == null || fullName.isBlank() ||
            email == null || email.isBlank() ||
            password == null || password.isEmpty()) {

            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "All fields are required: full_name, email, password.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        fullName = fullName.trim();
        email = email.trim();

        String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();

            String sql = "INSERT INTO users (full_name, email, password_hash) VALUES (?, ?, ?)";

            try (PreparedStatement ps = conn.prepareStatement(sql,
                    PreparedStatement.RETURN_GENERATED_KEYS)) {

                ps.setString(1, fullName);       
                ps.setString(2, email);          
                ps.setString(3, passwordHash);   

                ps.executeUpdate();

                ResultSet generatedKeys = ps.getGeneratedKeys();
                int newUserId = 0;
                if (generatedKeys.next()) {
                    newUserId = generatedKeys.getInt(1);
                }

                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("message", "Registration successful!");
                response.addProperty("user_id", newUserId);
                response.addProperty("full_name", fullName);

                CorsUtil.sendJsonResponse(exchange, 201, gson.toJson(response));
            }

        } catch (SQLException e) {

            if ("23000".equals(e.getSQLState())) {

                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "This email is already registered. Please use a different email or login.");
                CorsUtil.sendJsonResponse(exchange, 409, gson.toJson(error));
            } else {

                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Registration failed: " + e.getMessage());
                CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
            }
        }
    }

    private void handleLogin(HttpExchange exchange, JsonObject data) throws IOException {

        String email = getStringField(data, "email");
        String password = getStringField(data, "password");

        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Email and password are required.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        email = email.trim();

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();

            String sql = "SELECT user_id, full_name, email, password_hash FROM users WHERE email = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, email);

                try (ResultSet rs = ps.executeQuery()) {

                    if (rs.next()) {

                        String storedHash = rs.getString("password_hash");

                        if (BCrypt.checkpw(password, storedHash)) {

                            JsonObject response = new JsonObject();
                            response.addProperty("success", true);
                            response.addProperty("message", "Login successful!");
                            response.addProperty("user_id", rs.getInt("user_id"));
                            response.addProperty("full_name", rs.getString("full_name"));

                            CorsUtil.sendJsonResponse(exchange, 200, gson.toJson(response));
                        } else {

                            JsonObject error = new JsonObject();
                            error.addProperty("success", false);
                            error.addProperty("message", "Invalid email or password.");
                            CorsUtil.sendJsonResponse(exchange, 401, gson.toJson(error));
                        }
                    } else {

                        JsonObject error = new JsonObject();
                        error.addProperty("success", false);
                        error.addProperty("message", "Invalid email or password.");
                        CorsUtil.sendJsonResponse(exchange, 401, gson.toJson(error));
                    }
                }
            }

        } catch (SQLException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Login failed due to server error: " + e.getMessage());
            CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
        }
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {

        InputStream inputStream = exchange.getRequestBody();

        InputStreamReader isr = new InputStreamReader(inputStream, "UTF-8");

        BufferedReader reader = new BufferedReader(isr);

        StringBuilder sb = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }

        reader.close();

        return sb.toString();
    }

    private String getStringField(JsonObject data, String key) {
        if (data.has(key) && !data.get(key).isJsonNull()) {
            return data.get(key).getAsString();
        }
        return null;
    }
}
