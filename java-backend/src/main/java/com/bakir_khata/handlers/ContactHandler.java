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
import java.util.ArrayList; 
import java.util.List;      

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;

import com.bakir_khata.DatabaseHelper;

public class ContactHandler implements HttpHandler {

    private final Gson gson = new Gson();

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        if (CorsUtil.handleCors(exchange)) return;

        String method = exchange.getRequestMethod().toUpperCase();

        switch (method) {
            case "GET":
                getContacts(exchange);
                break;
            case "POST":
                addContact(exchange);
                break;
            case "PUT":
                updateContact(exchange);
                break;
            case "DELETE":
                deleteContact(exchange);
                break;
            default:

                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Method not allowed. Use GET, POST, PUT, or DELETE.");
                CorsUtil.sendJsonResponse(exchange, 405, gson.toJson(error));
                break;
        }
    }

    private void getContacts(HttpExchange exchange) throws IOException {

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

            String sql = "SELECT contact_id, contact_name, email, phone_number " +
                         "FROM contacts WHERE user_id = ? ORDER BY contact_name ASC";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);  

                try (ResultSet rs = ps.executeQuery()) {

                    JsonArray contactsArray = new JsonArray();

                    while (rs.next()) {
                        JsonObject contact = new JsonObject();
                        contact.addProperty("contact_id", rs.getInt("contact_id"));
                        contact.addProperty("contact_name", rs.getString("contact_name"));

                        String email = rs.getString("email");
                        if (email != null) {
                            contact.addProperty("email", email);
                        } else {
                            contact.add("email", null);
                        }

                        String phone = rs.getString("phone_number");
                        if (phone != null) {
                            contact.addProperty("phone_number", phone);
                        } else {
                            contact.add("phone_number", null);  
                        }

                        contactsArray.add(contact);
                    }

                    JsonObject response = new JsonObject();
                    response.addProperty("success", true);
                    response.add("contacts", contactsArray);

                    CorsUtil.sendJsonResponse(exchange, 200, gson.toJson(response));
                }
            }

        } catch (SQLException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Failed to fetch contacts: " + e.getMessage());
            CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
        }
    }

    private void addContact(HttpExchange exchange) throws IOException {

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

        if (!data.has("user_id") || data.get("user_id").isJsonNull() ||
            !data.has("contact_name") || data.get("contact_name").isJsonNull() ||
            data.get("contact_name").getAsString().trim().isEmpty()) {

            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Required fields: user_id, contact_name.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        int userId = data.get("user_id").getAsInt();
        String contactName = data.get("contact_name").getAsString().trim();

        String email = null;
        if (data.has("email") && !data.get("email").isJsonNull()) {
            email = data.get("email").getAsString().trim();
            if (email.isEmpty()) email = null;
        }

        String phoneNumber = null;
        if (data.has("phone_number") && !data.get("phone_number").isJsonNull()) {
            phoneNumber = data.get("phone_number").getAsString().trim();
            if (phoneNumber.isEmpty()) phoneNumber = null;
        }

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();

            String sql = "INSERT INTO contacts (user_id, contact_name, email, phone_number) VALUES (?, ?, ?, ?)";

            try (PreparedStatement ps = conn.prepareStatement(sql,
                    PreparedStatement.RETURN_GENERATED_KEYS)) {

                ps.setInt(1, userId);
                ps.setString(2, contactName);

                if (email != null) {
                    ps.setString(3, email);
                } else {
                    ps.setNull(3, java.sql.Types.VARCHAR);
                }

                if (phoneNumber != null) {
                    ps.setString(4, phoneNumber);
                } else {
                    ps.setNull(4, java.sql.Types.VARCHAR);
                }

                ps.executeUpdate();

                ResultSet generatedKeys = ps.getGeneratedKeys();
                int newContactId = 0;
                if (generatedKeys.next()) {
                    newContactId = generatedKeys.getInt(1);
                }

                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("message", "Contact added successfully!");
                response.addProperty("contact_id", newContactId);

                CorsUtil.sendJsonResponse(exchange, 201, gson.toJson(response));
            }

        } catch (SQLException e) {

            if ("23000".equals(e.getSQLState())) {
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Invalid user_id. The specified user does not exist.");
                CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            } else {
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("message", "Failed to add contact: " + e.getMessage());
                CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
            }
        }
    }

    private void updateContact(HttpExchange exchange) throws IOException {
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

        if (!data.has("user_id") || !data.has("contact_id") || !data.has("contact_name") ||
            data.get("contact_name").getAsString().trim().isEmpty()) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Required fields: user_id, contact_id, contact_name.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        int userId = data.get("user_id").getAsInt();
        int contactId = data.get("contact_id").getAsInt();
        String contactName = data.get("contact_name").getAsString().trim();

        String email = null;
        if (data.has("email") && !data.get("email").isJsonNull()) {
            email = data.get("email").getAsString().trim();
            if (email.isEmpty()) email = null;
        }

        String phoneNumber = null;
        if (data.has("phone_number") && !data.get("phone_number").isJsonNull()) {
            phoneNumber = data.get("phone_number").getAsString().trim();
            if (phoneNumber.isEmpty()) phoneNumber = null;
        }

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();
            String sql = "UPDATE contacts SET contact_name = ?, email = ?, phone_number = ? WHERE contact_id = ? AND user_id = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, contactName);
                if (email != null) {
                    ps.setString(2, email);
                } else {
                    ps.setNull(2, java.sql.Types.VARCHAR);
                }
                if (phoneNumber != null) {
                    ps.setString(3, phoneNumber);
                } else {
                    ps.setNull(3, java.sql.Types.VARCHAR);
                }
                ps.setInt(4, contactId);
                ps.setInt(5, userId);

                int rowsAffected = ps.executeUpdate();

                if (rowsAffected > 0) {
                    JsonObject response = new JsonObject();
                    response.addProperty("success", true);
                    response.addProperty("message", "Contact updated successfully!");
                    CorsUtil.sendJsonResponse(exchange, 200, gson.toJson(response));
                } else {
                    JsonObject error = new JsonObject();
                    error.addProperty("success", false);
                    error.addProperty("message", "Contact not found or you do not have permission to update it.");
                    CorsUtil.sendJsonResponse(exchange, 404, gson.toJson(error));
                }
            }
        } catch (SQLException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Failed to update contact: " + e.getMessage());
            CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
        }
    }

    private void deleteContact(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQueryParams(exchange);

        if (!params.containsKey("user_id") || !params.containsKey("contact_id")) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Missing required parameters: user_id, contact_id");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        int userId, contactId;
        try {
            userId = Integer.parseInt(params.get("user_id"));
            contactId = Integer.parseInt(params.get("contact_id"));
        } catch (NumberFormatException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "user_id and contact_id must be valid integers.");
            CorsUtil.sendJsonResponse(exchange, 400, gson.toJson(error));
            return;
        }

        try {
            Connection conn = DatabaseHelper.getInstance().getConnection();
            String sql = "DELETE FROM contacts WHERE contact_id = ? AND user_id = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, contactId);
                ps.setInt(2, userId);

                int rowsAffected = ps.executeUpdate();

                if (rowsAffected > 0) {
                    JsonObject response = new JsonObject();
                    response.addProperty("success", true);
                    response.addProperty("message", "Contact deleted successfully!");
                    CorsUtil.sendJsonResponse(exchange, 200, gson.toJson(response));
                } else {
                    JsonObject error = new JsonObject();
                    error.addProperty("success", false);
                    error.addProperty("message", "Contact not found or you do not have permission to delete it.");
                    CorsUtil.sendJsonResponse(exchange, 404, gson.toJson(error));
                }
            }
        } catch (SQLException e) {
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("message", "Failed to delete contact: " + e.getMessage());
            CorsUtil.sendJsonResponse(exchange, 500, gson.toJson(error));
        }
    }

    private Map<String, String> parseQueryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();

        String query = exchange.getRequestURI().getQuery();

        if (query != null && !query.isEmpty()) {

            String[] pairs = query.split("&");

            for (String pair : pairs) {

                String[] keyValue = pair.split("=", 2);  

                if (keyValue.length == 2) {
                    params.put(keyValue[0], keyValue[1]);
                } else if (keyValue.length == 1) {
                    params.put(keyValue[0], "");  
                }
            }
        }
        return params;
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
}
