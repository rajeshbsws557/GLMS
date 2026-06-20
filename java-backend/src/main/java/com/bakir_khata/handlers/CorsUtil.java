package com.bakir_khata.handlers;

import com.sun.net.httpserver.HttpExchange;  
import com.sun.net.httpserver.Headers;       

import java.io.IOException;                  
import java.io.OutputStream;                 

public class CorsUtil {

    private CorsUtil() {}

    public static boolean handleCors(HttpExchange exchange) throws IOException {

        Headers headers = exchange.getResponseHeaders();

        headers.set("Access-Control-Allow-Origin", "*");

        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");

        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization");

        headers.set("Access-Control-Allow-Credentials", "true");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {

            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;  
        }

        return false;
    }

    public static void sendJsonResponse(HttpExchange exchange,
                                         int statusCode,
                                         String jsonBody) throws IOException {

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

        byte[] responseBytes = jsonBody.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.close();
    }
}
