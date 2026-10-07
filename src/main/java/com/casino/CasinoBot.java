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
import java.util.concurrent.*;

public class CasinoBot extends TelegramLongPollingBot {

    private static final String TOKEN = System.getenv("BOT_TOKEN") != null ? 
            System.getenv("BOT_TOKEN") : "8846203742:AAH6phjqvFPTDd6Y6xakWSu5RY7t5DiJjTA";

    private static final Set<Long> ADMIN_IDS = Set.of(7964831905L, 7432218242L);

    private static final long[] BET_AMOUNTS = {
            10_000_000L, 50_000_000L, 100_000_000L, 200_000_000L,
            500_000_000L, 1_000_000_000L, 2_000_000_000L, 5_000_000_000L,
            7_000_000_000L, 10_000_000_000L, 15_000_000_000L
    };

    private static final long[] BAICAO_BET_OPTIONS = {
            10_000_000L, 50_000_000L, 100_000_000L, 200_000_000L, 500_000_000L, 1_000_000_000L
    };

    private final Map<Long, DiceGame> games = new ConcurrentHashMap<>();
    private final Map<Long, BaiCaoGame> baicaoGames = new ConcurrentHashMap<>();
    private final Map<Long, LixiSession> lixiSessions = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastLixiWinners = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    private static class LixiSession {
        long amount;
        boolean claimed = false;
        int clickCount = 0;
        Set<Long> clickedUsers = ConcurrentHashMap.newKeySet();
        String baseText;

        LixiSession(long amount, String baseText) {
            this.amount = amount;
            this.baseText = baseText;
        }
    }

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
        long startTime = System.currentTimeMillis();
        ScheduledFuture<?> countdownTask = null;
    }

    private static class Card {
        String rank;
        String suit;
        Card(String rank, String suit) {
            this.rank = rank;
            this.suit = suit;
        }
        @Override
        public String toString() {
            return rank + suit;
        }
    }

    private static class BaiCaoGame {
        long hostId;
        long betAmount;
        Map<Long, String> players = new ConcurrentHashMap<>();
        List<Long> playerOrder = new ArrayList<>();
        Integer messageId = null;
        Integer lastTagMessageId = null;
        long startTime = System.currentTimeMillis();
        ScheduledFuture<?> lobbyCountdown = null;
        
        boolean playing = false;
        int currentTurnIndex = 0;
        long highestBet = 0;
        long totalPot = 0;
        Map<Long, Long> playerBets = new ConcurrentHashMap<>();
        Map<Long, List<Card>> cards = new ConcurrentHashMap<>();
        Set<Long> folded = ConcurrentHashMap.newKeySet();
        ScheduledFuture<?> turnCountdown = null;
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
        } else if (text.startsWith("/setvip") && ADMIN_IDS.contains(user.getId())) {
            handleSetVip(message, true);
        } else if (text.startsWith("/unvip") && ADMIN_IDS.contains(user.getId())) {
            handleSetVip(message, false);
        } else if (text.startsWith("/tru") && ADMIN_IDS.contains(user.getId())) {
            handleTru(message);
        } else if (text.startsWith("/reset") && ADMIN_IDS.contains(user.getId())) {
            DiceGame g = games.remove(chatId);
            if (g != null && g.countdownTask != null) g.countdownTask.cancel(true);
            BaiCaoGame bc = baicaoGames.remove(chatId);
            if (bc != null) {
                if (bc.lobbyCountdown != null) bc.lobbyCountdown.cancel(true);
                if (bc.turnCountdown != null) bc.turnCountdown.cancel(true);
            }
            lixiSessions.remove(chatId);
            sendMessage(chatId, "🔄 <b>Đã reset ván chơi!</b>\n💰 Tiền cược đã được hoàn lại.");
            sendMainMenu(chatId);
        }
    }

    private void handleCallback(org.telegram.telegrambots.meta.api.objects.CallbackQuery query) {
        long chatId = query.getMessage().getChatId();
        User user = query.getFrom();
        String data = query.getData();
        Database.ensureUser(user.getId(), user.getUserName(), user.getFirstName());

        if (data.equals("new_game")) {
            if (games.containsKey(chatId) || baicaoGames.containsKey(chatId)) {
                answerAlert(query.getId(), "⚠️ Đang có ván thi đấu diễn ra!");
                return;
            }
            deleteMessage(chatId, query.getMessage().getMessageId());
            createDiceGame(chatId);
        } else if (data.equals("bc_select_bet")) {
            if (games.containsKey(chatId) || baicaoGames.containsKey(chatId)) {
                answerAlert(query.getId(), "⚠️ Đang có ván thi đấu diễn ra!");
                return;
            }
            showBaiCaoSelectBet(chatId, query.getMessage().getMessageId());
        } else if (data.startsWith("bc_create:")) {
            long betAmt = Long.parseLong(data.split(":")[1]);
            createBaiCaoLobby(chatId, user, betAmt);
        } else if (data.equals("bc_join")) {
            joinBaiCaoLobby(chatId, user, query.getId());
        } else if (data.equals("bc_deal")) {
            dealBaiCaoCards(chatId, user, query.getId());
        } else if (data.equals("bc_view_cards")) {
            showMyCards(chatId, user, query.getId());
        } else if (data.equals("bc_fold")) {
            handleBaiCaoAction(chatId, user, "fold", 0);
        } else if (data.equals("bc_call")) {
            handleBaiCaoAction(chatId, user, "call", 0);
        } else if (data.startsWith("bc_raise:")) {
            long raiseAmt = Long.parseLong(data.split(":")[1]);
            handleBaiCaoAction(chatId, user, "raise", raiseAmt);
        } else if (data.equals("take_dealer")) {
            takeDealer(chatId, user, query.getId());
        } else if (data.startsWith("bet:")) {
            String[] parts = data.split(":");
            placeBet(chatId, user, parts[1], Long.parseLong(parts[2]), query.getId());
        } else if (data.equals("roll")) {
            rollDiceManual(chatId, user, query.getId());
        } else if (data.equals("claim_lixi")) {
            claimLixi(chatId, user, query.getId(), query.getMessage());
        } else if (data.equals("balance")) {
            long bal = Database.getBalance(user.getId());
            boolean vip = Database.isVip(user.getId());
            sendMessage(chatId, "💰 Số dư của " + user.getFirstName() + (vip ? " ⭐<b>VIP</b>" : "") + ": <b>" + formatFullMoney(bal) + "</b>");
        } else if (data.equals("top")) {
            long[] admins = ADMIN_IDS.stream().mapToLong(l -> l).toArray();
            sendMessage(chatId, Database.getTopText(admins));
        }
    }

    private void createDiceGame(long chatId) {
        DiceGame game = new DiceGame();
        games.put(chatId, game);

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(getDiceGameText(game, 40));
        msg.setParseMode("HTML");
        msg.setReplyMarkup(getDealerKeyboard());

        try {
            Message sent = execute(msg);
            game.messageId = sent.getMessageId();
            game.countdownTask = scheduler.scheduleAtFixedRate(() -> updateDiceGameTimer(chatId), 5, 5, TimeUnit.SECONDS);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private String getDiceGameText(DiceGame game, int remainingSeconds) {
        StringBuilder sb = new StringBuilder();
        sb.append("🎲 <b>GAME XÚC XẮC SẴN SÀNG</b>\n\n");
        if (game.dealerId == null) {
            sb.append("👑 <b>Cầm cái:</b> Chưa có\n");
        } else {
            sb.append("👑 <b>Cầm cái:</b> ").append(game.dealerName).append("\n");
            sb.append("⏳ <b>Thời gian còn lại:</b> ").append(Math.max(0, remainingSeconds)).append("s\n");
        }

        sb.append("\n🔥 <b>Tài:</b> 11–18\n💧 <b>Xỉu:</b> 3–10\n");
        sb.append("\n💰 <b>Chọn mức cược bên dưới.</b>\n⚠ Mỗi người chỉ được cược 1 lần.");

        if (!game.bets.isEmpty()) {
            sb.append("\n\n📋 <b>DANH SÁCH CƯỢC</b>\n");
            int idx = 1;
            for (Bet bet : game.bets.values()) {
                String icon = bet.side.equals("T") ? "🔴" : "🔵";
                String sideName = bet.side.equals("T") ? "Tài" : "Xỉu";
                sb.append(idx).append("- ").append(icon).append(" ").append(bet.name)
                  .append(" → ").append(sideName).append(": ").append(formatMoney(bet.amount)).append("\n");
                idx++;
            }
        }
        return sb.toString();
    }

    private void updateDiceGameTimer(long chatId) {
        DiceGame game = games.get(chatId);
        if (game == null || game.rolling || game.messageId == null) return;

        long elapsed = (System.currentTimeMillis() - game.startTime) / 1000;
        int remaining = (int) (40 - elapsed);

        if (remaining <= 0) {
            if (game.countdownTask != null) game.countdownTask.cancel(true);
            if (!game.bets.isEmpty() && game.dealerId != null) {
                rollDiceAuto(chatId);
            } else {
                games.remove(chatId);
                sendMessage(chatId, "⚠️ <b>Hết 40 giây! Ván đấu bị hủy do không đủ điều kiện.</b>");
                sendMainMenu(chatId);
            }
            return;
        }

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(game.messageId);
        edit.setText(getDiceGameText(game, remaining));
        edit.setParseMode("HTML");
        edit.setReplyMarkup(game.dealerId == null ? getDealerKeyboard() : getBettingKeyboard());

        try { execute(edit); } catch (Exception ignored) {}
    }

    private void showBaiCaoSelectBet(long chatId, int messageId) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText("🃏 <b>CHỌN MỨC CƯỢC CHO BÀN BÀI TỐ</b>\n\nMỗi người chơi tham gia sẽ đặt cược số tiền sàn này.");
        edit.setParseMode("HTML");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (long amt : BAICAO_BET_OPTIONS) {
            row.add(createBtn("👉 " + formatMoney(amt), "bc_create:" + amt));
            if (row.size() == 2) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) rows.add(row);

        edit.setReplyMarkup(new InlineKeyboardMarkup(rows));
        try { execute(edit); } catch (Exception ignored) {}
    }

    private void createBaiCaoLobby(long chatId, User user, long betAmount) {
        long bal = Database.getBalance(user.getId());
        if (bal < betAmount) {
            sendMessage(chatId, "❌ Số dư của bạn không đủ để tạo bàn cược mức " + formatMoney(betAmount) + "!");
            return;
        }

        BaiCaoGame game = new BaiCaoGame();
        game.hostId = user.getId();
        game.betAmount = betAmount;
        game.players.put(user.getId(), user.getFirstName());
        game.playerOrder.add(user.getId());
        baicaoGames.put(chatId, game);

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(getBaiCaoLobbyText(game, 30));
        msg.setParseMode("HTML");
        msg.setReplyMarkup(getBaiCaoLobbyKeyboard());

        try {
            Message sent = execute(msg);
            game.messageId = sent.getMessageId();
            game.lobbyCountdown = scheduler.schedule(() -> checkBaiCaoLobbyTimeout(chatId), 30, TimeUnit.SECONDS);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private String getBaiCaoLobbyText(BaiCaoGame game, int remaining) {
        StringBuilder sb = new StringBuilder();
        sb.append("🃏 <b>BÀN BÀI TỐ (BÀI CÀO 3 LÁ)</b>\n\n");
        sb.append("💰 <b>Cược sàn:</b> ").append(formatMoney(game.betAmount)).append(" / người\n");
        sb.append("👥 <b>Số người tham gia:</b> ").append(game.players.size()).append("\n");
        sb.append("⏳ <b>Thời gian chờ:</b> ").append(remaining).append("s\n\n");
        sb.append("📋 <b>DANH SÁCH NGƯỜI CHƠI:</b>\n");
        
        int idx = 1;
        for (long pId : game.playerOrder) {
            sb.append(idx).append(". ").append(game.players.get(pId)).append("\n");
            idx++;
        }
        sb.append("\n👉 Bấm <b>THAM GIA</b> để vào bàn.\n👉 Chủ phòng bấm <b>CHIA BÀI</b> khi đủ người.");
        return sb.toString();
    }

    private InlineKeyboardMarkup getBaiCaoLobbyKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(
                        createBtn("✋ THAM GIA", "bc_join"),
                        createBtn("🃏 CHIA BÀI", "bc_deal")
                )
        ));
    }

    private void checkBaiCaoLobbyTimeout(long chatId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || game.playing) return;

        if (game.playerOrder.size() < 2) {
            baicaoGames.remove(chatId);
            sendMessage(chatId, "⚠️ <b>Bàn Bài Tố tự động hủy do không đủ từ 2 người chơi sau 30 giây!</b>");
            sendMainMenu(chatId);
        } else {
            startBaiCaoGame(chatId);
        }
    }

    private void joinBaiCaoLobby(long chatId, User user, String queryId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || game.playing) {
            answerAlert(queryId, "⚠️ Bàn cược không tồn tại hoặc đã bắt đầu!");
            return;
        }

        if (game.players.containsKey(user.getId())) {
            answerAlert(queryId, "⚠️ Bạn đã ở trong bàn này rồi!");
            return;
        }

        long bal = Database.getBalance(user.getId());
        if (bal < game.betAmount) {
            answerAlert(queryId, "❌ Số dư không đủ để tham gia!");
            return;
        }

        game.players.put(user.getId(), user.getFirstName());
        game.playerOrder.add(user.getId());

        answerAlert(queryId, "✅ Tham gia bàn thành công!");
        updateBaiCaoLobbyMessage(chatId);
    }

    private void updateBaiCaoLobbyMessage(long chatId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || game.messageId == null) return;

        long elapsed = (System.currentTimeMillis() - game.startTime) / 1000;
        int remaining = (int) (30 - elapsed);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(game.messageId);
        edit.setText(getBaiCaoLobbyText(game, Math.max(0, remaining)));
        edit.setParseMode("HTML");
        edit.setReplyMarkup(getBaiCaoLobbyKeyboard());

        try { execute(edit); } catch (Exception ignored) {}
    }

    private void dealBaiCaoCards(long chatId, User user, String queryId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || game.playing) {
            answerAlert(queryId, "⚠️ Bàn cược không tồn tại!");
            return;
        }

        if (user.getId() != game.hostId) {
            answerAlert(queryId, "❌ Chỉ chủ phòng mới có quyền chia bài!");
            return;
        }

        if (game.playerOrder.size() < 2) {
            answerAlert(queryId, "⚠️ Cần tối thiểu 2 người chơi mới có thể chia bài!");
            return;
        }

        if (game.lobbyCountdown != null) game.lobbyCountdown.cancel(true);
        answerAlert(queryId, "🃏 Đang tiến hành chia bài...");
        deleteMessage(chatId, game.messageId);
        startBaiCaoGame(chatId);
    }

    private void startBaiCaoGame(long chatId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null) return;

        game.playing = true;
        for (long pId : game.playerOrder) {
            Database.changeBalance(pId, -game.betAmount);
            game.playerBets.put(pId, game.betAmount);
        }
        game.totalPot = game.betAmount * game.playerOrder.size();
        game.highestBet = game.betAmount;

        String[] suits = {"♠️", "♥️", "♣️", "♦️"};
        String[] ranks = {"2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A"};

        List<Card> deck = new ArrayList<>();
        for (String s : suits) {
            for (String r : ranks) {
                deck.add(new Card(r, s));
            }
        }
        Collections.shuffle(deck);

        for (long pId : game.playerOrder) {
            List<Card> hand = new ArrayList<>();
            hand.add(deck.remove(0));
            hand.add(deck.remove(0));
            hand.add(deck.remove(0));
            game.cards.put(pId, hand);
        }

        sendBaiCaoPlayingMessage(chatId);
    }

    private void sendBaiCaoPlayingMessage(long chatId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null) return;

        long currentUserId = game.playerOrder.get(game.currentTurnIndex);
        String currentName = game.players.get(currentUserId);

        StringBuilder sb = new StringBuilder();
        sb.append("🃏 <b>BÀN BÀI TỐ ĐANG DIỄN RA</b>\n\n");
        sb.append("🏆 <b>Tổng Hũ:</b> ").append(formatFullMoney(game.totalPot)).append("\n");
        sb.append("👉 <b>Đến lượt:</b> ").append(currentName).append(" (⏳ 50s)\n\n");
        sb.append("📋 <b>Trạng thái:</b>");
        for (long pId : game.playerOrder) {
            String status = game.folded.contains(pId) ? "❌ Đã Úp bài" : "💵 Cược: " + formatMoney(game.playerBets.get(pId));
            sb.append("\n- ").append(game.players.get(pId)).append(": ").append(status);
        }

        // Tạo bàn phím đầy đủ các nút Tố, Theo, Xem, Úp bài
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(createBtn("👁 XEM BÀI", "bc_view_cards")));
        rows.add(List.of(
                createBtn("✅ THEO", "bc_call"),
                createBtn("❌ ÚP BÀI", "bc_fold")
        ));
        rows.add(List.of(
                createBtn("➕ Tố 50M", "bc_raise:50000000"),
                createBtn("➕ Tố 100M", "bc_raise:100000000")
        ));

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(sb.toString());
        msg.setParseMode("HTML");
        msg.setReplyMarkup(new InlineKeyboardMarkup(rows));

        try {
            if (game.lastTagMessageId != null) {
                deleteMessage(chatId, game.lastTagMessageId);
            }

            Message sent = execute(msg);
            game.messageId = sent.getMessageId();
            game.lastTagMessageId = sent.getMessageId();

            game.turnCountdown = scheduler.schedule(() -> handleBaiCaoTimeout(chatId, currentUserId), 50, TimeUnit.SECONDS);

        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void showMyCards(long chatId, User user, String queryId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || !game.playing) {
            answerAlert(queryId, "⚠️ Ván đấu không tồn tại!");
            return;
        }

        List<Card> hand = game.cards.get(user.getId());
        if (hand == null) {
            answerAlert(queryId, "❌ Bạn không có bài trong ván này!");
            return;
        }

        String cardsStr = hand.get(0) + " " + hand.get(1) + " " + hand.get(2);
        answerAlert(queryId, "🃏 Bài của bạn: [ " + cardsStr + " ]");
    }

    private void handleBaiCaoAction(long chatId, User user, String action, long raiseAmount) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || !game.playing) return;

        long currentUserId = game.playerOrder.get(game.currentTurnIndex);
        if (user.getId() != currentUserId) return;

        if (game.turnCountdown != null) game.turnCountdown.cancel(true);

        if (action.equals("fold")) {
            game.folded.add(user.getId());
            sendMessage(chatId, "❌ " + user.getFirstName() + " đã Úp bài!");
        } else if (action.equals("call")) {
            long need = game.highestBet - game.playerBets.get(user.getId());
            if (need > 0) {
                Database.changeBalance(user.getId(), -need);
                game.playerBets.put(user.getId(), game.highestBet);
                game.totalPot += need;
            }
            sendMessage(chatId, "✅ " + user.getFirstName() + " đã Theo!");
        } else if (action.equals("raise")) {
            long totalNeed = (game.highestBet - game.playerBets.get(user.getId())) + raiseAmount;
            Database.changeBalance(user.getId(), -totalNeed);
            long newBet = game.playerBets.get(user.getId()) + totalNeed;
            game.playerBets.put(user.getId(), newBet);
            game.highestBet = newBet;
            game.totalPot += totalNeed;
            sendMessage(chatId, "🚀 " + user.getFirstName() + " đã Tố thêm " + formatMoney(raiseAmount) + "!");
        }

        advanceBaiCaoTurn(chatId);
    }

    private void handleBaiCaoTimeout(long chatId, long userId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || !game.playing) return;

        long currentUserId = game.playerOrder.get(game.currentTurnIndex);
        if (currentUserId == userId) {
            game.folded.add(userId);
            sendMessage(chatId, "⏰ <b>Quá 50 giây không thao tác, người chơi đã tự động úp bài!</b>");
            advanceBaiCaoTurn(chatId);
        }
    }

    private void advanceBaiCaoTurn(long chatId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null) return;

        List<Long> activePlayers = new ArrayList<>();
        for (long pId : game.playerOrder) {
            if (!game.folded.contains(pId)) activePlayers.add(pId);
        }

        if (activePlayers.size() <= 1) {
            long winnerId = activePlayers.isEmpty() ? game.playerOrder.get(0) : activePlayers.get(0);
            Database.changeBalance(winnerId, game.totalPot);
            
            StringBuilder winMsg = new StringBuilder();
            winMsg.append("🏆 <b>KẾT THÚC VÁN BÀI TỐ</b>\n\n");
            winMsg.append("👑 <b>Người chiến thắng:</b> ").append(game.players.get(winnerId)).append("\n");
            winMsg.append("💰 <b>Tiền thưởng:</b> +").append(formatFullMoney(game.totalPot)).append("\n\n");
            winMsg.append("📋 <b>Bài của các người chơi:</b>");

            for (long pId : game.playerOrder) {
                List<Card> h = game.cards.get(pId);
                winMsg.append("\n- ").append(game.players.get(pId)).append(": [ ").append(h.get(0)).append(" ").append(h.get(1)).append(" ").append(h.get(2)).append(" ]");
            }

            sendMessage(chatId, winMsg.toString());
            baicaoGames.remove(chatId);
            sendMainMenu(chatId);
            return;
        }

        do {
            game.currentTurnIndex = (game.currentTurnIndex + 1) % game.playerOrder.size();
        } while (game.folded.contains(game.playerOrder.get(game.currentTurnIndex)));

        deleteMessage(chatId, game.messageId);
        sendBaiCaoPlayingMessage(chatId);
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
        game.startTime = System.currentTimeMillis();

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

    private void rollDiceManual(long chatId, User user, String queryId) {
        DiceGame game = games.get(chatId);
        if (game == null || !user.getId().equals(game.dealerId) || game.rolling) return;
        answerAlert(queryId, "🎲 Đang tung xúc xắc!");
        executeRoll(chatId);
    }

    private void rollDiceAuto(long chatId) {
        executeRoll(chatId);
    }

    private void executeRoll(long chatId) {
        DiceGame game = games.get(chatId);
        if (game == null || game.rolling) return;

        game.rolling = true;
        if (game.countdownTask != null) game.countdownTask.cancel(true);

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
        if (game.countdownTask != null) game.countdownTask.cancel(true);

        long dealerProfit = 0;
        long totalBetsSum = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("🎲 <b>KẾT QUẢ XÚC XẮC</b>\n\n");
        sb.append("💥 Tổng điểm: <b>").append(total).append("</b> - ").append(result.equals("T") ? "🔥 TÀI" : "💧 XỈU").append("\n\n");

        for (Bet b : game.bets.values()) {
            totalBetsSum += b.amount;
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

        long lixiAmount = (long)(totalBetsSum * 0.006);
        InlineKeyboardMarkup lixiMarkup = null;
        if (lixiAmount > 0) {
            lixiSessions.put(chatId, new LixiSession(lixiAmount, sb.toString()));
            lixiMarkup = new InlineKeyboardMarkup(List.of(List.of(createBtn("🧧 NHẬN LÌ XÌ (" + formatMoney(lixiAmount) + ") 🧧", "claim_lixi"))));
        }

        sendMessageWithMarkup(chatId, sb.toString(), lixiMarkup);
        sendMainMenu(chatId);
    }

    private void claimLixi(long chatId, User user, String queryId, Message message) {
        LixiSession session = lixiSessions.get(chatId);
        if (session == null || session.claimed) {
            answerAlert(queryId, "🧧 Lì xì đã được nhận hoặc hết hạn!");
            return;
        }

        if (Long.valueOf(user.getId()).equals(lastLixiWinners.get(chatId))) {
            answerAlert(queryId, "⚠️ Bạn đã nhận lì xì ván trước rồi!");
            return;
        }

        if (Database.getBalance(user.getId()) >= 10_000_000_000L) {
            answerAlert(queryId, "⚠️ Số dư từ 10B trở lên không được nhận lì xì!");
            return;
        }

        if (session.clickedUsers.contains(user.getId())) {
            answerAlert(queryId, "⚠️ Bạn đã mở lì xì ván này rồi!");
            return;
        }

        session.clickedUsers.add(user.getId());
        session.clickCount++;

        boolean isWin = session.clickCount >= 5 || new Random().nextDouble() < 0.5;
        if (isWin) {
            session.claimed = true;
            Database.changeBalance(user.getId(), session.amount);
            lastLixiWinners.put(chatId, user.getId());

            answerAlert(queryId, "🎉 Bạn trúng lì xì " + formatMoney(session.amount) + "!");
            String updateText = session.baseText + "\n\n🧧 <b>LÌ XÌ:</b> 🎉 Chúc mừng " + user.getFirstName() + " đã nhặt thành công " + formatMoney(session.amount) + "!";

            EditMessageText edit = new EditMessageText();
            edit.setChatId(String.valueOf(chatId));
            edit.setMessageId(message.getMessageId());
            edit.setText(updateText);
            edit.setParseMode("HTML");
            try { execute(edit); } catch (Exception ignored) {}
        } else {
            answerAlert(queryId, "❌ Trượt rồi! Chúc bạn may mắn lần sau (" + session.clickCount + "/5).");
        }
    }

    private void handleTransfer(Message message) {
        if (message.getReplyToMessage() == null) {
            sendMessage(message.getChatId(), "⚠️ Hãy TRẢ LỜI tin nhắn người nhận.");
            return;
        }

        String[] parts = message.getText().split(" ");
        if (parts.length < 2) return;

        long amount = parseMoney(parts[1]);
        if (amount <= 0) return;

        long senderId = message.getFrom().getId();
        long receiverId = message.getReplyToMessage().getFrom().getId();

        if (senderId == receiverId) {
            sendMessage(message.getChatId(), "⚠️ Không thể chuyển điểm cho chính mình.");
            return;
        }

        boolean isAdmin = ADMIN_IDS.contains(senderId);
        boolean isVip = Database.isVip(senderId);

        if (!isAdmin && !isVip && amount > 5_000_000_000L) {
            sendMessage(message.getChatId(), "⚠️ Người dùng thường chỉ được chuyển tối đa <b>5B</b> mỗi lần!");
            return;
        }

        if (!isAdmin && Database.getBalance(senderId) < amount) {
            sendMessage(message.getChatId(), "❌ Số dư không đủ!");
            return;
        }

        if (!isAdmin) {
            Database.changeBalance(senderId, -amount);
        }
        Database.changeBalance(receiverId, amount);

        sendMessage(message.getChatId(), "💸 Chuyển thành công " + formatMoney(amount) + " cho " + message.getReplyToMessage().getFrom().getFirstName() + "!");
    }

    private void handleSetVip(Message message, boolean status) {
        if (message.getReplyToMessage() == null) return;
        long targetId = message.getReplyToMessage().getFrom().getId();
        Database.setVipStatus(targetId, status);
        sendMessage(message.getChatId(), status ? "⭐ Đã nâng cấp VIP thành công!" : "🔻 Đã hạ quyền VIP!");
    }

    private void handleTru(Message message) {
        if (message.getReplyToMessage() == null) return;
        String[] parts = message.getText().split(" ");
        if (parts.length < 2) return;
        long amount = parseMoney(parts[1]);
        long targetId = message.getReplyToMessage().getFrom().getId();

        Database.changeBalance(targetId, -amount);
        sendMessage(message.getChatId(), "🔻 Đã trừ " + formatMoney(amount) + " của " + message.getReplyToMessage().getFrom().getFirstName());
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
                createBtn("🃏 BÀI TỐ", "bc_select_bet")
        ));
        rows.add(List.of(
                createBtn("💰 SỐ DƯ", "balance"),
                createBtn("🏆 BẢNG XẾP HẠNG", "top")
        ));
        markup.setKeyboard(rows);
        msg.setReplyMarkup(markup);

        try { execute(msg); } catch (Exception ignored) {}
    }

    private void updateGameMessage(long chatId) {
        DiceGame game = games.get(chatId);
        if (game == null) return;

        long elapsed = (System.currentTimeMillis() - game.startTime) / 1000;
        int remaining = (int) (40 - elapsed);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(game.messageId);
        edit.setText(getDiceGameText(game, remaining));
        edit.setParseMode("HTML");
        edit.setReplyMarkup(getBettingKeyboard());

        try { execute(edit); } catch (Exception ignored) {}
    }

    private InlineKeyboardMarkup getDealerKeyboard() {
        return new InlineKeyboardMarkup(List.of(List.of(createBtn("👉 CẦM CÁI 👈", "take_dealer"))));
    }

    private InlineKeyboardMarkup getBettingKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (long amt : BET_AMOUNTS) {
            rows.add(List.of(
                    createBtn("🔴 Tài " + formatMoney(amt), "bet:T:" + amt),
                    createBtn("🔵 Xỉu " + formatMoney(amt), "bet:X:" + amt)
            ));
        }
        rows.add(List.of(createBtn("🎲 TUNG XÚC XẮC", "roll")));
        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardButton createBtn(String text, String data) {
        InlineKeyboardButton btn = new InlineKeyboardButton(text);
        btn.setCallbackData(data);
        return btn;
    }

    private void sendMessage(long chatId, String text) {
        sendMessageWithMarkup(chatId, text, null);
    }

    private void sendMessageWithMarkup(long chatId, String text, InlineKeyboardMarkup markup) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        msg.setParseMode("HTML");
        if (markup != null) msg.setReplyMarkup(markup);
        try { execute(msg); } catch (Exception ignored) {}
    }

    private void deleteMessage(long chatId, int messageId) {
        try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception ignored) {}
    }

    private void answerAlert(String queryId, String text) {
        AnswerCallbackQuery ans = new AnswerCallbackQuery();
        ans.setCallbackQueryId(queryId);
        ans.setText(text);
        ans.setShowAlert(true);
        try { execute(ans); } catch (Exception ignored) {}
    }

    private String formatMoney(long amount) {
        if (amount < 0) return "-" + formatMoney(Math.abs(amount));
        if (amount >= 1_000_000_000_000L) {
            double val = amount / 1_000_000_000_000.0;
            return (val == (long) val) ? String.format("%dT", (long) val) : String.format("%.1fT", val);
        }
        if (amount >= 1_000_000_000L) {
            double val = amount / 1_000_000_000.0;
            return (val == (long) val) ? String.format("%dB", (long) val) : String.format("%.1fB", val);
        }
        if (amount >= 1_000_000L) {
            double val = amount / 1_000_000.0;
            return (val == (long) val) ? String.format("%dM", (long) val) : String.format("%.1fM", val);
        }
        if (amount >= 1_000L) {
            double val = amount / 1_000.0;
            return (val == (long) val) ? String.format("%dK", (long) val) : String.format("%.1fK", val);
        }
        return String.valueOf(amount);
    }

    private String formatFullMoney(long amount) {
        return formatMoney(amount) + " đ";
    }

    private long parseMoney(String text) {
        try {
            text = text.trim().toUpperCase().replace(",", "");
            if (text.endsWith("K")) return (long)(Double.parseDouble(text.replace("K", "")) * 1_000);
            if (text.endsWith("M")) return (long)(Double.parseDouble(text.replace("M", "")) * 1_000_000);
            if (text.endsWith("B")) return (long)(Double.parseDouble(text.replace("B", "")) * 1_000_000_000);
            if (text.endsWith("T")) return (long)(Double.parseDouble(text.replace("T", "")) * 1_000_000_000_000L);
            return Long.parseLong(text);
        } catch (Exception e) {
            return 0;
        }
    }
}
