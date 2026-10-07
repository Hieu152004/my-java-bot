package com.casino;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) {
        Database.initDb();
        try {
            TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
            api.registerBot(new CasinoBot());
            System.out.println("🤖 Bot Casino (Java) đã khởi tạo thành công!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
