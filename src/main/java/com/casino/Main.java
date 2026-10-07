package com.casino;

import com.sun.net.httpserver.HttpServer;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.OutputStream;
import java.net.InetSocketAddress;

public class Main {
    public static void main(String[] args) {
        // Khởi tạo SQLite
        Database.initDb();

        // Mở port giả lập để Render xác nhận Web Service thành công
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", exchange -> {
                String response = "Bot is running!";
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            });
            server.start();
            System.out.println("🌐 Fake HTTP server listening on port " + port);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Khởi chạy Telegram Bot
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new CasinoBot());
            System.out.println("🤖 Bot Casino (Java) đã khởi tạo thành công!");
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}
