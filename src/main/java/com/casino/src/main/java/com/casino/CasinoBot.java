package com.casino;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendDice;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CasinoBot extends TelegramLongPollingBot {

    private static final String TOKEN = System.getenv("BOT_TOKEN") != null ? 
            System.getenv("BOT_TOKEN") : "8846203742:AAH6phjqvFPTDd6Y6xakWSu5RY7t5DiJjTA";
    private static final Set<Long> ADMIN_IDS = Set.of(7964831905L, 7432218242L);

    // Bảng điều khiển cược Xúc Xắc
    private static final long[] BET_AMOUNTS = {
            10_000_000L, 50_000_000L, 100_000_000L, 200_000_000L,
            500_000_000L, 1_000_000_000L, 2_000_000_000L, 5_000_000_000L
    };

    // State lưu game Xúc Xắc đang chạy
    private final Map<Long, DiceGame> games = new ConcurrentHashMap<>();

    private static class Bet {
        long userId;
        String name;
        String side;
        long amount;

        Bet(long userId, String name, String side, long amount) {
            this.userId = userId;
            this.name = name;
            this.side = side;
            this.amount = amount;
        }
    }

    private static class DiceGame {
        Long dealerId = null;
        String dealerName = null;
        Map<Long, Bet> bets = new ConcurrentHashMap<>();
        Integer messageId = null;
        boolean rolling = false;
    }

    @Override
    public String getBotUsername() {
        return "Casino_Game_Bot";
    }

    @Override
    public String getBotToken() {
        return TOKEN;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            handleMessage(update.getMessage());
        } else if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
        }
    }

    private void handleMessage(Message message) {
        User user = message.getFrom();
        long chatId = message.getChatId();
        Database.ensureUser(user.getId(), user.getUserName(), user.getFirstName());

        String text = message.getText();
        if (text == null) return;

        if (text.startsWith("/start") || text.startsWith("/restart")) {
            sendMainMenu(chatId);
        } else if (text.startsWith("/ckd")) {
            handleTransfer(message);
        }
    }

    private void handleCallback(org.telegram.telegrambots.meta.api.objects.CallbackQuery query) {
        long chatId = query.getMessage().getChatId();
        User user = query.getFrom();
        String data = query.getData();
        Database.ensureUser(user.getId(), user.getUserName(), user.getFirstName());

        if (data.equals("new_game")) {
            if (games.containsKey(chatId)) {
                answerAlert(query.getId(), "⚠️ Đang có ván thi đấu diễn ra!");
                return;
            }
            deleteMessage(chatId, query.getMessage().getMessageId());
            createDiceGame(chatId);
        } else if (data.equals("take_dealer")) {
            takeDealer(chatId, user, query.getId());
        } else if (data.startsWith("bet:")) {
            String[] parts = data.split(":");
            placeBet(chatId, user, parts[1], Long.parseLong(parts[2]), query.getId());
        } else if (data.equals("roll")) {
            rollDice(chatId, user, query.getId());
        } else if (data.equals("balance")) {
            long bal = Database.getBalance(user.getId());
            sendMessage(chatId, "💰 Số dư của bạn: <b>" + formatMoney(bal) + " đ</b>");
        }
    }

    private void createDiceGame(long chatId) {
        DiceGame game = new DiceGame();
        games.put(chatId, game);

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText("🎲 <b>GAME XÚC XẮC SẴN SÀNG</b>\n\n👑 <b>Cầm cái:</b> Chưa có\n\n🔥 <b>Tài:</b> 11–18\n💧 <b>Xỉu:</b> 3–10");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(getDealerKeyboard());

        try {
            Message sent = execute(msg);
            game.messageId = sent.getMessageId();
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void takeDealer(long chatId, User user, String queryId) {
        DiceGame game = games.get(chatId);
        if (game == null || game.dealerId != null) {
            answerAlert(queryId, "⚠️ Đã có người cầm cái hoặc ván kết thúc!");
            return;
        }

        long bal = Database.getBalance(user.getId());
        if (bal < 10_000_000L) {
            answerAlert(queryId, "👉 Số dư không đủ điều kiện cầm cái! Yêu cầu tối thiểu 10M.");
            return;
        }

        game.dealerId = user.getId();
        game.dealerName = user.getFirstName();

        updateGameMessage(chatId);
        answerAlert(queryId, "👑 Bạn đã cầm cái thành công!");
    }

    private void placeBet(long chatId, User user, String side, long amount, String queryId) {
        DiceGame game = games.get(chatId);
        if (game == null || game.rolling) return;

        if (game.dealerId != null && game.dealerId.equals(user.getId())) {
            answerAlert(queryId, "⚠️ Cầm cái không được cược.");
            return;
        }

        if (game.bets.containsKey(user.getId())) {
            answerAlert(queryId, "👉 Bạn chỉ được cược 1 lần mỗi ván!");
            return;
        }

        long bal = Database.getBalance(user.getId());
        if (bal < amount) {
            answerAlert(queryId, "👉 Số dư của bạn không đủ!");
            return;
        }

        Database.changeBalance(user.getId(), -amount);
        game.bets.put(user.getId(), new Bet(user.getId(), user.getFirstName(), side, amount));

        answerAlert(queryId, "👉 Đặt cược thành công!");
        updateGameMessage(chatId);
    }

    private void rollDice(long chatId, User user, String queryId) {
        DiceGame game = games.get(chatId);
        if (game == null || !user.getId().equals(game.dealerId) || game.rolling) return;

        game.rolling = true;
        answerAlert(queryId, "🎲 Đang tung xúc xắc!");

        new Thread(() -> {
            try {
                int total = 0;
                for (int i = 0; i < 3; i++) {
                    SendDice dice = new SendDice();
                    dice.setChatId(String.valueOf(chatId));
                    dice.setEmoji("🎲");
                    Message m = execute(dice);
                    total += m.getDice().getValue();
                    Thread.sleep(1500);
                }

                String result = (total >= 11) ? "T" : "X";
                settleGame(chatId, total, result);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void settleGame(long chatId, int total, String result) {
        DiceGame game = games.remove(chatId);
        if (game == null) return;

        long dealerProfit = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("🎲 <b>KẾT QUẢ XÚC XẮC</b>\n\n");
        sb.append("💥 Tổng điểm: <b>").append(total).append("</b> - ").append(result.equals("T") ? "🔥 TÀI" : "💧 XỈU").append("\n\n");

        for (Bet b : game.bets.values()) {
            if (b.side.equals(result)) {
                Database.changeBalance(b.userId, b.amount * 2);
                dealerProfit -= b.amount;
                sb.append("✅ ").append(b.name).append(" thắng ").append(formatMoney(b.amount)).append("\n");
            } else {
                dealerProfit += b.amount;
                sb.append("❌ ").append(b.name).append(" thua ").append(formatMoney(b.amount)).append("\n");
            }
        }

        if (game.dealerId != null) {
            Database.changeBalance(game.dealerId, dealerProfit);
            sb.append("\n👑 <b>Cầm cái:</b> ").append(dealerProfit >= 0 ? "+" : "").append(formatMoney(dealerProfit));
        }

        sendMessage(chatId, sb.toString());
        sendMainMenu(chatId);
    }

    private void handleTransfer(Message message) {
        if (message.getReplyToMessage() == null) {
            sendMessage(message.getChatId(), "⚠️ Hãy TRẢ LỜI tin nhắn người nhận.");
            return;
        }

        String[] parts = message.getText().split(" ");
        if (parts.length < 2) return;

        long amount = Long.parseLong(parts[1]);
        long senderId = message.getFrom().getId();
        long receiverId = message.getReplyToMessage().getFrom().getId();

        if (Database.getBalance(senderId) < amount) {
            sendMessage(message.getChatId(), "❌ Số dư không đủ!");
            return;
        }

        Database.changeBalance(senderId, -amount);
        Database.changeBalance(receiverId, amount);
        sendMessage(message.getChatId(), "💸 Chuyển thành công " + formatMoney(amount) + " đ!");
    }

    private void sendMainMenu(long chatId) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText("🎰 <b>HỆ THỐNG GAME CASINO (JAVA)</b>\n\nChọn game muốn chơi bên dưới:");
        msg.setParseMode("HTML");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                createBtn("🎲 XÚC XẮC", "new_game"),
                createBtn("💰 SỐ DƯ", "balance")
        ));
        markup.setKeyboard(rows);
        msg.setReplyMarkup(markup);

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void updateGameMessage(long chatId) {
        DiceGame game = games.get(chatId);
        if (game == null) return;

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(game.messageId);
        edit.setText("🎲 <b>GAME XÚC XẮC</b>\n\n👑 <b>Cầm cái:</b> " + game.dealerName + "\n👥 Số lượt cược: " + game.bets.size());
        edit.setParseMode("HTML");
        edit.setReplyMarkup(getBettingKeyboard());

        try {
            execute(edit);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private InlineKeyboardMarkup getDealerKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(List.of(createBtn("👉 CẦM CÁI 👈", "take_dealer"))));
        return markup;
    }

    private InlineKeyboardMarkup getBettingKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (long amt : BET_AMOUNTS) {
            rows.add(List.of(
                    createBtn("🔴 Tài " + formatMoney(amt), "bet:T:" + amt),
                    createBtn("🔵 Xỉu " + formatMoney(amt), "bet:X:" + amt)
            ));
        }
        rows.add(List.of(createBtn("🎲 TUNG XÚC XẮC", "roll")));
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardButton createBtn(String text, String data) {
        InlineKeyboardButton btn = new InlineKeyboardButton(text);
        btn.setCallbackData(data);
        return btn;
    }

    private void sendMessage(long chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        msg.setParseMode("HTML");
        try { execute(msg); } catch (Exception ignored) {}
    }

    private void deleteMessage(long chatId, int messageId) {
        DeleteMessage del = new DeleteMessage(String.valueOf(chatId), messageId);
        try { execute(del); } catch (Exception ignored) {}
    }

    private void answerAlert(String queryId, String text) {
        AnswerCallbackQuery ans = new AnswerCallbackQuery();
        ans.setCallbackQueryId(queryId);
        ans.setText(text);
        ans.setShowAlert(true);
        try { execute(ans); } catch (Exception ignored) {}
    }

    private String formatMoney(long amount) {
        return String.format("%,d", amount).replace(",", ".");
    }
}
