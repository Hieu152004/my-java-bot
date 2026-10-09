package com.casino;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendDice;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.*;

public class CasinoBot extends TelegramLongPollingBot {

    private static final String TOKEN = System.getenv("BOT_TOKEN") != null ? 
            System.getenv("BOT_TOKEN") : "8846203742:AAH6phjqvFPTDd6Y6xakWSu5RY7t5DiJjTA";

    private static final Set<Long> ADMIN_IDS = Set.of(7964831905L, 7432218242L);
    private final long botStartupTime = System.currentTimeMillis() / 1000L;

    private static final long[] BET_AMOUNTS = {
            500_000_000L, 1_000_000_000L, 2_000_000_000L, 5_000_000_000L,
            7_000_000_000L, 10_000_000_000L, 15_000_000_000L,
            20_000_000_000L, 50_000_000_000L
    };

    private static final long[] BAICAO_BET_OPTIONS = {
            10_000_000L, 50_000_000L, 100_000_000L, 200_000_000L, 500_000_000L, 1_000_000_000L
    };

    private final Map<Long, DiceGame> games = new ConcurrentHashMap<>();
    private final Map<Long, BaiCaoGame> baicaoGames = new ConcurrentHashMap<>();
    private final Map<Long, LixiSession> lixiSessions = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastLixiWinners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> lastMenuMessageIds = new ConcurrentHashMap<>();
    private final List<String> diceHistory = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(6);
    private final ExecutorService telegramExecutor = Executors.newFixedThreadPool(8);
    private final ExecutorService callbackExecutor = Executors.newFixedThreadPool(8);
    private final ExecutorService updateExecutor = Executors.newFixedThreadPool(16);
    private final ExecutorService priorityExecutor = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<Long, Long> ensuredUsers = new ConcurrentHashMap<>();

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
        long dealerBalance = 0;
        boolean dealerClaimingWindow = false;
        ScheduledFuture<?> dealerClaimTask = null;
        Map<Long, Bet> bets = new ConcurrentHashMap<>();
        Integer messageId = null;
        boolean rolling = false;
        long startTime = System.currentTimeMillis();
        ScheduledFuture<?> countdownTask = null;
    }

    private static class Card implements Comparable<Card> {
        String rank;
        String suit;
        int rankValue;
        int suitValue;

        Card(String rank, String suit) {
            this.rank = rank;
            this.suit = suit;
            this.rankValue = parseRank(rank);
            this.suitValue = parseSuit(suit);
        }

        private int parseRank(String r) {
            switch (r) {
                case "J": return 11;
                case "Q": return 12;
                case "K": return 13;
                case "A": return 14;
                default: return Integer.parseInt(r);
            }
        }

        private int parseSuit(String s) {
            switch (s) {
                case "♥️": return 4;
                case "♦️": return 3;
                case "♣️": return 2;
                case "♠️": return 1;
                default: return 0;
            }
        }

        @Override
        public String toString() {
            return rank + suit;
        }

        @Override
        public int compareTo(Card o) {
            if (this.rankValue != o.rankValue) {
                return Integer.compare(this.rankValue, o.rankValue);
            }
            return Integer.compare(this.suitValue, o.suitValue);
        }
    }

    private static class HandScore implements Comparable<HandScore> {
        int type; // 1: Điểm, 2: 3 Tây, 3: Liêng, 4: Cào
        int primaryValue;
        int subValue;
        String description;

        HandScore(int type, int primaryValue, int subValue, String description) {
            this.type = type;
            this.primaryValue = primaryValue;
            this.subValue = subValue;
            this.description = description;
        }

        @Override
        public int compareTo(HandScore o) {
            if (this.type != o.type) {
                return Integer.compare(this.type, o.type);
            }
            if (this.primaryValue != o.primaryValue) {
                return Integer.compare(this.primaryValue, o.primaryValue);
            }
            return Integer.compare(this.subValue, o.subValue);
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
        Set<Long> allInPlayers = ConcurrentHashMap.newKeySet();
        
        long turnStartTime = 0;
        ScheduledFuture<?> turnCountdown = null;
        Set<Long> actedInCurrentRound = ConcurrentHashMap.newKeySet();
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
        try {
            ExecutorService target = isPriorityCommand(update) ? priorityExecutor : updateExecutor;
            target.execute(() -> {
                try {
                    processUpdate(update);
                } catch (Throwable t) {
                    System.err.println("[BOT] Lỗi xử lý update: " + t.getMessage());
                    t.printStackTrace();
                }
            });
        } catch (RejectedExecutionException ignored) {
            System.err.println("[BOT] Update worker quá tải, bỏ qua update.");
        }
    }

    private boolean isPriorityCommand(Update update) {
        if (!update.hasMessage() || update.getMessage().getText() == null) return false;
        String text = update.getMessage().getText().trim();
        return text.startsWith("/reset") || text.startsWith("/restart");
    }

    private void processUpdate(Update update) {
        if (update.hasMessage()) {
            Message message = update.getMessage();
            if (message.getDate() != null && message.getDate() < botStartupTime) return;
            handleMessage(message);
        } else if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
        }
    }

    private void ensureUserCached(User user) {
        long now = System.currentTimeMillis();
        Long last = ensuredUsers.get(user.getId());
        if (last == null || now - last > 60_000L) {
            Database.ensureUser(user.getId(), user.getUserName(), user.getFirstName());
            ensuredUsers.put(user.getId(), now);
        }
    }

    private void handleMessage(Message message) {
        User user = message.getFrom();
        long chatId = message.getChatId();
        ensureUserCached(user);

        String text = message.getText();
        if (text == null) return;

        if (text.startsWith("/start") || text.startsWith("/restart")) {
            if (games.containsKey(chatId) || baicaoGames.containsKey(chatId)) {
                sendMessage(chatId, "⚠️ Đang có ván thi đấu diễn ra! Không thể mở menu mới.");
                return;
            }
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
            Integer oldMenuId = lastMenuMessageIds.remove(chatId);
            if (oldMenuId != null) deleteMessage(chatId, oldMenuId);

            DiceGame g = games.remove(chatId);
            if (g != null) {
                if (g.countdownTask != null) g.countdownTask.cancel(true);
                if (g.dealerClaimTask != null) g.dealerClaimTask.cancel(true);
                for (Bet b : g.bets.values()) {
                    Database.changeBalance(b.userId, b.amount);
                }
                if (g.messageId != null) deleteMessage(chatId, g.messageId);
            }
            
            BaiCaoGame bc = baicaoGames.get(chatId);
            if (bc != null) {
                synchronized (bc) {
                    bc.playing = false;
                    baicaoGames.remove(chatId, bc);
                    if (bc.lobbyCountdown != null) bc.lobbyCountdown.cancel(true);
                    if (bc.turnCountdown != null) bc.turnCountdown.cancel(true);

                    for (Map.Entry<Long, Long> entry : bc.playerBets.entrySet()) {
                        long pId = entry.getKey();
                        long betAmt = entry.getValue();
                        if (betAmt > 0) Database.changeBalance(pId, betAmt);
                    }

                    if (bc.lastTagMessageId != null) deleteMessage(chatId, bc.lastTagMessageId);
                    if (bc.messageId != null) deleteMessage(chatId, bc.messageId);
                }
            }
            lixiSessions.remove(chatId);
            sendMessage(chatId, "🔄 <b>Đã reset ván chơi!</b>\n💰 Toàn bộ tiền cược đã được hoàn trả đầy đủ.");
            sendMainMenu(chatId);
        }
    }

    private void handleCallback(org.telegram.telegrambots.meta.api.objects.CallbackQuery query) {
        long chatId = query.getMessage().getChatId();
        User user = query.getFrom();
        String data = query.getData();
        ensureUserCached(user);

        if (data.equals("new_game")) {
            if (games.containsKey(chatId) || baicaoGames.containsKey(chatId)) {
                answerAlert(query.getId(), "⚠️ Đã có ván thi đấu đang diễn ra trong nhóm này!");
                return;
            }
            deleteMessage(chatId, query.getMessage().getMessageId());
            createDiceGame(chatId);
        } else if (data.equals("bc_select_bet")) {
            if (games.containsKey(chatId) || baicaoGames.containsKey(chatId)) {
                answerAlert(query.getId(), "⚠️ Đã có ván thi đấu đang diễn ra trong nhóm này!");
                return;
            }
            showBaiCaoSelectBet(chatId, query.getMessage().getMessageId());
        } else if (data.startsWith("bc_create:")) {
            long betAmt = Long.parseLong(data.split(":")[1]);
            deleteMessage(chatId, query.getMessage().getMessageId());
            createBaiCaoLobby(chatId, user, betAmt);
        } else if (data.equals("bc_join")) {
            joinBaiCaoLobby(chatId, user, query.getId());
        } else if (data.equals("bc_deal")) {
            dealBaiCaoCards(chatId, user, query.getId());
        } else if (data.equals("bc_view_cards")) {
            showMyCards(chatId, user, query.getId());
        } else if (data.equals("bc_fold")) {
            handleBaiCaoAction(chatId, user, "fold", 0, query.getId());
        } else if (data.equals("bc_call")) {
            handleBaiCaoAction(chatId, user, "call", 0, query.getId());
        } else if (data.startsWith("bc_raise_pct:")) {
            int percent = Integer.parseInt(data.split(":")[1]);
            handleBaiCaoAction(chatId, user, "raise_pct", percent, query.getId());
        } else if (data.startsWith("bc_raise:")) {
            long raiseAmt = Long.parseLong(data.split(":")[1]);
            handleBaiCaoAction(chatId, user, "raise", raiseAmt, query.getId());
        } else if (data.startsWith("bc_allin:")) {
            int percent = Integer.parseInt(data.split(":")[1]);
            handleBaiCaoAction(chatId, user, "allin", percent, query.getId());
        } else if (data.equals("take_dealer")) {
            takeDealer(chatId, user, query.getId());
        } else if (data.startsWith("bet:")) {
            String[] parts = data.split(":");
            placeBet(chatId, user, parts[1], Long.parseLong(parts[2]), query.getId());
        } else if (data.equals("roll")) {
            rollDiceManual(chatId, user, query.getId());
        } else if (data.equals("claim_lixi")) {
            MaybeInaccessibleMessage maybeMsg = query.getMessage();
            Message msg = (maybeMsg instanceof Message) ? (Message) maybeMsg : null;
            claimLixi(chatId, user, query.getId(), msg);
        } else if (data.equals("balance")) {
            long bal = Database.getBalance(user.getId());
            answerAlert(query.getId(), "Điểm hiện tại của bạn là: " + formatDetailedMoney(bal) + " đ.");
        } else if (data.equals("top")) {
            answerAlert(query.getId(), "📊 Đang tải bảng xếp hạng...");
            long[] admins = ADMIN_IDS.stream().mapToLong(l -> l).toArray();
            telegramExecutor.execute(() -> {
                String top = Database.getTopText(admins);
                sendMessage(chatId, top);
            });
        }
    }

    private String getMention(long userId, String name) {
        return String.format("<a href=\"tg://user?id=%d\">%s</a>", userId, name);
    }

    private void createDiceGame(long chatId) {
        DiceGame game = new DiceGame();
        if (games.putIfAbsent(chatId, game) != null) {
            sendMessage(chatId, "⚠️ Đã có bàn Xúc Xắc đang mở sẵn!");
            return;
        }

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(getDiceGameText(game, 40));
        msg.setParseMode("HTML");
        msg.setReplyMarkup(getDealerKeyboard());

        telegramExecutor.execute(() -> {
            try {
                Message sent = execute(msg);
                game.messageId = sent.getMessageId();
            } catch (Exception e) {
                games.remove(chatId, game);
                System.err.println("[BOT] Không gửi được menu Xúc Xắc: " + e.getMessage());
            }
        });
    }

    private String getDiceGameText(DiceGame game, int remainingSeconds) {
        StringBuilder sb = new StringBuilder();
        sb.append("🎲 <b>GAME XÚC XẮC SẴN SÀNG</b>\n\n");
        if (game.dealerId == null) {
            sb.append("👑 <b>Cầm cái:</b> Chưa có (Bấm CẦM CÁI để tranh quyền)\n");
        } else if (game.dealerClaimingWindow) {
            sb.append("👑 <b>Cầm cái tạm thời:</b> ").append(game.dealerName)
              .append(" (Số dư: ").append(formatDetailedMoney(game.dealerBalance)).append(" đ.)\n");
            sb.append("⏳ <b>Đang chờ tranh cái (7s)... Mọi người tiếp tục bấm CẦM CÁI để tranh quyền!</b>\n");
        } else {
            sb.append("👑 <b>Cầm cái chính thức:</b> ").append(game.dealerName)
              .append(" (Số dư: ").append(formatDetailedMoney(game.dealerBalance)).append(" đ.)\n");
            sb.append("⏳ <b>Thời gian còn lại:</b> ").append(Math.max(0, remainingSeconds)).append("s\n");
        }

        sb.append("\n🔥 <b>Tài:</b> 11–18\n💧 <b>Xỉu:</b> 3–10\n");
        
        if (!diceHistory.isEmpty()) {
            sb.append("\n📜 <b>Lịch sử:</b> ");
            int start = Math.max(0, diceHistory.size() - 10);
            for (int i = start; i < diceHistory.size(); i++) {
                sb.append(diceHistory.get(i)).append(" ");
            }
            sb.append("\n");
        }

        if (game.dealerClaimingWindow || game.dealerId == null) {
            sb.append("\n👉 <b>Đang trong thời gian tranh cái. Hãy bấm nút CẦM CÁI bên dưới!</b>");
        } else {
            sb.append("\n💰 <b>Chọn mức cược bên dưới.</b>\n⚠ Mỗi người chỉ được cược 1 lần.");

            if (!game.bets.isEmpty()) {
                sb.append("\n\n📋 <b>DANH SÁCH CƯỢC</b>\n");
                int idx = 1;
                for (Bet bet : game.bets.values()) {
                    String icon = bet.side.equals("T") ? "🔴" : "🔵";
                    String sideName = bet.side.equals("T") ? "Tài" : "Xỉu";
                    sb.append(idx).append("- ").append(icon).append(" ").append(bet.name)
                      .append(" → ").append(sideName).append(": ").append(formatDetailedMoney(bet.amount)).append(" đ.\n");
                    idx++;
                }
            }
        }
        return sb.toString();
    }

    private void updateDiceGameTimer(long chatId) {
        DiceGame game = games.get(chatId);
        if (game == null || game.rolling || game.messageId == null) return;
        if (game.dealerClaimingWindow) return;

        long elapsed = (System.currentTimeMillis() - game.startTime) / 1000;
        int remaining = (int) (40 - elapsed);

        if (remaining <= 0) {
            if (game.countdownTask != null) game.countdownTask.cancel(true);
            if (!game.bets.isEmpty() && game.dealerId != null) {
                rollDiceAuto(chatId);
            } else {
                games.remove(chatId);
                deleteMessage(chatId, game.messageId);
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
        edit.setReplyMarkup(getBettingKeyboard());

        telegramExecutor.execute(() -> { try { execute(edit); } catch (Exception ignored) {} });
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
            row.add(createBtn("👉 " + formatShortMoney(amt), "bc_create:" + amt));
            if (row.size() == 2) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) rows.add(row);

        edit.setReplyMarkup(new InlineKeyboardMarkup(rows));
        telegramExecutor.execute(() -> { try { execute(edit); } catch (Exception ignored) {} });
    }

    private void createBaiCaoLobby(long chatId, User user, long betAmount) {
        long bal = Database.getBalance(user.getId());
        if (bal < betAmount) {
            sendMessage(chatId, "❌ Số dư của bạn không đủ để tạo bàn cược mức " + formatDetailedMoney(betAmount) + " đ.!");
            return;
        }

        BaiCaoGame game = new BaiCaoGame();
        game.hostId = user.getId();
        game.betAmount = betAmount;
        game.players.put(user.getId(), user.getFirstName());
        game.playerOrder.add(user.getId());

        if (baicaoGames.putIfAbsent(chatId, game) != null) {
            sendMessage(chatId, "⚠️ Đã có bàn Bài Tố đang mở trong nhóm này!");
            return;
        }

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(getBaiCaoLobbyText(game, 30));
        msg.setParseMode("HTML");
        msg.setReplyMarkup(getBaiCaoLobbyKeyboard());

        telegramExecutor.execute(() -> {
            try {
                Message sent = execute(msg);
                game.messageId = sent.getMessageId();
                game.lobbyCountdown = scheduler.schedule(() -> checkBaiCaoLobbyTimeout(chatId), 30, TimeUnit.SECONDS);
            } catch (Exception e) {
                baicaoGames.remove(chatId, game);
                System.err.println("[BOT] Không gửi được lobby Bài Tố: " + e.getMessage());
            }
        });
    }

    private String getBaiCaoLobbyText(BaiCaoGame game, int remaining) {
        StringBuilder sb = new StringBuilder();
        sb.append("🃏 <b>BÀN BÀI TỐ (BÀI CÀO 3 LÁ)</b>\n\n");
        sb.append("💰 <b>Cược sàn:</b> ").append(formatDetailedMoney(game.betAmount)).append(" đ. / người\n");
        sb.append("👥 <b>Số người tham gia:</b> ").append(game.players.size()).append("\n");
        sb.append("⏳ <b>Thời gian chờ:</b> ").append(remaining).append("s\n\n");
        sb.append("📋 <b>DANH SÁCH NGƯỜI CHƠI:</b>\n");
        
        int idx = 1;
        for (long pId : game.playerOrder) {
            sb.append(idx).append(". ").append(getMention(pId, game.players.get(pId))).append("\n");
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
            if (game.messageId != null) deleteMessage(chatId, game.messageId);
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

        telegramExecutor.execute(() -> { try { execute(edit); } catch (Exception ignored) {} });
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
        game.messageId = null;
        startBaiCaoGame(chatId);
    }

    private void startBaiCaoGame(long chatId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null) return;

        if (!Database.tryDeductBalances(game.playerOrder, game.betAmount)) {
            sendMessage(chatId, "❌ Không thể bắt đầu Bài Tố: có người không còn đủ tiền cược sàn. Ván đã được hủy, không ai bị trừ tiền.");
            baicaoGames.remove(chatId);
            if (game.lobbyCountdown != null) game.lobbyCountdown.cancel(true);
            if (game.messageId != null) deleteMessage(chatId, game.messageId);
            sendMainMenu(chatId);
            return;
        }

        game.playing = true;
        game.allInPlayers.clear();
        game.actedInCurrentRound.clear();
        for (long pId : game.playerOrder) {
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
        game.turnStartTime = System.currentTimeMillis();

        if (game.messageId == null) {
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(chatId));
            msg.setText(getBaiCaoPlayingText(game, 50));
            msg.setParseMode("HTML");
            msg.setReplyMarkup(getBaiCaoPlayingKeyboard(game));
            telegramExecutor.execute(() -> {
                try {
                    Message sent = execute(msg);
                    game.messageId = sent.getMessageId();
                } catch (Exception e) {
                    System.err.println("[BOT] Không gửi được màn Bài Tố: " + e.getMessage());
                }
            });
        } else {
            updateBaiCaoPlayingMessage(chatId, 50);
        }

        try {
            if (game.lastTagMessageId != null) {
                deleteMessage(chatId, game.lastTagMessageId);
            }
            SendMessage tagMsg = new SendMessage();
            tagMsg.setChatId(String.valueOf(chatId));
            tagMsg.setText("👉 <b>Đến lượt:</b> " + getMention(currentUserId, game.players.get(currentUserId)) + " (⏳ 50s)");
            tagMsg.setParseMode("HTML");
            telegramExecutor.execute(() -> {
                try {
                    Message tagSent = execute(tagMsg);
                    game.lastTagMessageId = tagSent.getMessageId();
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}

        if (game.turnCountdown != null) game.turnCountdown.cancel(true);
        game.turnCountdown = scheduler.scheduleAtFixedRate(() -> updateBaiCaoTurnTimer(chatId), 5, 5, TimeUnit.SECONDS);
    }

    private String getBaiCaoPlayingText(BaiCaoGame game, int remainingSeconds) {
        long currentUserId = game.playerOrder.get(game.currentTurnIndex);
        String currentMention = getMention(currentUserId, game.players.get(currentUserId));

        StringBuilder sb = new StringBuilder();
        sb.append("🃏 <b>BÀN BÀI TỐ ĐANG DIỄN RA</b>\n\n");
        sb.append("🏆 <b>Tổng Hũ:</b> ").append(formatDetailedMoney(game.totalPot)).append(" đ.\n");
        sb.append("👉 <b>Đến lượt:</b> ").append(currentMention).append(" (⏳ ").append(Math.max(0, remainingSeconds)).append("s)\n\n");
        sb.append("📋 <b>Trạng thái bàn:</b>");
        for (long pId : game.playerOrder) {
            String status = game.folded.contains(pId) ? "❌ Đã Úp bài" : "🟢 Đang chơi";
            sb.append("\n- ").append(getMention(pId, game.players.get(pId))).append(": ").append(status);
        }
        return sb.toString();
    }

    private InlineKeyboardMarkup getBaiCaoPlayingKeyboard(BaiCaoGame game) {
        long currentUserId = game.playerOrder.get(game.currentTurnIndex);
        long userBet = game.playerBets.getOrDefault(currentUserId, 0L);
        long needAmount = game.highestBet - userBet;

        String callText = needAmount > 0 ? "✅ THEO THÊM " + formatShortMoney(needAmount) : "✅ THEO (XEM)";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(createBtn("👁 XEM BÀI", "bc_view_cards"), createBtn("❌ ÚP BÀI", "bc_fold")));
        rows.add(List.of(createBtn(callText, "bc_call")));
        rows.add(List.of(
                createBtn("➕ Tố 100M", "bc_raise:100000000"),
                createBtn("➕ Tố 200M", "bc_raise:200000000"),
                createBtn("➕ Tố 500M", "bc_raise:500000000")
        ));
        rows.add(List.of(
                createBtn("📈 Tố 50% số dư", "bc_raise_pct:50")
        ));
        rows.add(List.of(
                createBtn("➕ Tố 1B", "bc_raise:1000000000"),
                createBtn("➕ Tố 5B", "bc_raise:5000000000"),
                createBtn("➕ Tố 10B", "bc_raise:10000000000")
        ));
        rows.add(List.of(
                createBtn("🚀 TẤT TAY", "bc_allin:100")
        ));
        return new InlineKeyboardMarkup(rows);
    }

    private void updateBaiCaoPlayingMessage(long chatId, int remainingSeconds) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || game.messageId == null) return;

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(game.messageId);
        edit.setText(getBaiCaoPlayingText(game, remainingSeconds));
        edit.setParseMode("HTML");
        edit.setReplyMarkup(getBaiCaoPlayingKeyboard(game));

        telegramExecutor.execute(() -> { try { execute(edit); } catch (Exception ignored) {} });
    }

    private void updateBaiCaoTurnTimer(long chatId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || !game.playing || game.messageId == null) return;

        long elapsed = (System.currentTimeMillis() - game.turnStartTime) / 1000;
        int remaining = (int) (50 - elapsed);

        if (remaining <= 0) {
            if (game.turnCountdown != null) game.turnCountdown.cancel(true);
            long timeoutUserId = game.playerOrder.get(game.currentTurnIndex);
            handleBaiCaoTimeout(chatId, timeoutUserId);
            return;
        }

        updateBaiCaoPlayingMessage(chatId, remaining);
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

        HandScore score = evaluateHand(hand);
        String cardsStr = hand.get(0) + " " + hand.get(1) + " " + hand.get(2);
        answerAlert(queryId, "🃏 Bài của bạn: [ " + cardsStr + " ]\n📊 Loại bài: " + score.description);
    }

    private void handleBaiCaoAction(long chatId, User user, String action, long param, String queryId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || !game.playing) {
            if (queryId != null) answerAlert(queryId, "⚠️ Ván đấu đã kết thúc!");
            return;
        }

        synchronized (game) {
            long userId = user.getId();
            if (!game.players.containsKey(userId)) {
                if (queryId != null) answerAlert(queryId, "❌ Bạn không tham gia bàn này!");
                return;
            }

            if (action.equals("fold")) {
                if (game.folded.contains(userId)) {
                    if (queryId != null) answerAlert(queryId, "⚠️ Bạn đã úp bài rồi!");
                    return;
                }
                game.folded.add(userId);
                game.actedInCurrentRound.remove(userId);
                sendMessage(chatId, "❌ " + getMention(userId, user.getFirstName()) + " đã Úp bài!");
                if (queryId != null) answerAlert(queryId, "✅ Đã úp bài thành công!");

                List<Long> activePlayers = getActiveBaiCaoPlayers(game);
                if (activePlayers.size() <= 1) {
                    if (game.turnCountdown != null) game.turnCountdown.cancel(true);
                    if (game.lastTagMessageId != null) deleteMessage(chatId, game.lastTagMessageId);
                    endBaiCaoGame(chatId, activePlayers);
                    return;
                }

                long currentUserId = game.playerOrder.get(game.currentTurnIndex);
                if (userId == currentUserId) {
                    if (game.turnCountdown != null) game.turnCountdown.cancel(true);
                    if (game.lastTagMessageId != null) deleteMessage(chatId, game.lastTagMessageId);
                    advanceBaiCaoTurn(chatId);
                } else {
                    updateBaiCaoPlayingMessage(chatId, 50);
                }
                return;
            }

            long currentUserId = game.playerOrder.get(game.currentTurnIndex);
            if (userId != currentUserId) {
                if (queryId != null) answerAlert(queryId, "❌ Chưa tới lượt của bạn! Bạn có thể ÚP BÀI trước.");
                return;
            }
            if (game.allInPlayers.contains(userId)) {
                if (queryId != null) answerAlert(queryId, "⚠️ Bạn đã TẤT TAY, không cần cược thêm.");
                return;
            }

            long userBal = Database.getBalance(userId);
            long userCurrentBet = game.playerBets.getOrDefault(userId, 0L);
            boolean actionAccepted = false;

            if (action.equals("call")) {
                long need = Math.max(0L, game.highestBet - userCurrentBet);

                if (need == 0) {
                    game.actedInCurrentRound.add(userId);
                    actionAccepted = true;
                    sendMessage(chatId, "👁️ " + getMention(userId, user.getFirstName()) + " đã Xem!");
                } else if (userBal <= 0) {
                    game.allInPlayers.add(userId);
                    game.actedInCurrentRound.add(userId);
                    actionAccepted = true;
                    sendMessage(chatId, "🔥 " + getMention(userId, user.getFirstName()) + " đã hết tiền và được tính là TẤT TAY.");
                } else if (userBal < need) {
                    long actual = userBal;
                    if (!Database.tryChangeBalance(userId, -actual)) {
                        if (queryId != null) answerAlert(queryId, "❌ Không thể cập nhật số dư, vui lòng thử lại.");
                        return;
                    }
                    game.playerBets.put(userId, userCurrentBet + actual);
                    game.totalPot += actual;
                    game.allInPlayers.add(userId);
                    game.actedInCurrentRound.add(userId);
                    actionAccepted = true;
                    sendMessage(chatId, "🔥 " + getMention(userId, user.getFirstName()) + " không đủ tiền theo nên đã TẤT TAY toàn bộ " + formatDetailedMoney(actual) + " đ.! ");
                } else {
                    if (!Database.tryChangeBalance(userId, -need)) {
                        if (queryId != null) answerAlert(queryId, "❌ Số dư vừa thay đổi, không đủ để Theo. Vui lòng thử lại.");
                        return;
                    }
                    game.playerBets.put(userId, userCurrentBet + need);
                    game.totalPot += need;
                    game.actedInCurrentRound.add(userId);
                    actionAccepted = true;
                    if (userBal == need) game.allInPlayers.add(userId);
                    sendMessage(chatId, "✅ " + getMention(userId, user.getFirstName()) + " đã Theo!");
                }
            } else if (action.equals("raise_pct")) {
                if (param != 50) {
                    if (queryId != null) answerAlert(queryId, "❌ Mức tố phần trăm không hợp lệ!");
                    return;
                }
                if (userBal <= 0) {
                    if (queryId != null) answerAlert(queryId, "❌ Số dư của bạn đã hết, không thể tố thêm.");
                    return;
                }
                long raiseAmt = userBal / 2;
                if (raiseAmt <= 0) {
                    if (queryId != null) answerAlert(queryId, "❌ Số dư quá nhỏ để Tố 50%.");
                    return;
                }
                long needToCall = Math.max(0L, game.highestBet - userCurrentBet);
                long totalNeed = needToCall + raiseAmt;
                if (userBal < totalNeed) {
                    if (queryId != null) answerAlert(queryId, "❌ Không đủ tiền để Theo + Tố 50% số dư. Cần " + formatDetailedMoney(totalNeed) + " đ. hoặc chọn TẤT TAY.");
                    return;
                }
                if (!Database.tryChangeBalance(userId, -totalNeed)) {
                    if (queryId != null) answerAlert(queryId, "❌ Số dư vừa thay đổi, vui lòng thử lại.");
                    return;
                }
                long newBet = userCurrentBet + totalNeed;
                game.playerBets.put(userId, newBet);
                game.highestBet = newBet;
                game.totalPot += totalNeed;
                game.actedInCurrentRound.clear();
                game.actedInCurrentRound.add(userId);
                if (userBal == totalNeed) game.allInPlayers.add(userId);
                actionAccepted = true;
                sendMessage(chatId, "📈 " + getMention(userId, user.getFirstName()) + " đã Tố 50% số dư: thêm " + formatDetailedMoney(raiseAmt) + " đ.! ");
            } else if (action.equals("raise")) {
                if (param <= 0) {
                    if (queryId != null) answerAlert(queryId, "❌ Mức tố không hợp lệ!");
                    return;
                }
                long needToCall = Math.max(0L, game.highestBet - userCurrentBet);
                long totalNeed = needToCall + param;
                if (userBal < totalNeed) {
                    if (queryId != null) answerAlert(queryId, "❌ Không đủ tiền để Tố. Cần " + formatDetailedMoney(totalNeed) + " đ. hoặc chọn TẤT TAY.");
                    return;
                }
                if (!Database.tryChangeBalance(userId, -totalNeed)) {
                    if (queryId != null) answerAlert(queryId, "❌ Số dư vừa thay đổi, vui lòng thử lại.");
                    return;
                }
                long newBet = userCurrentBet + totalNeed;
                game.playerBets.put(userId, newBet);
                game.highestBet = newBet;
                game.totalPot += totalNeed;
                game.actedInCurrentRound.clear();
                game.actedInCurrentRound.add(userId);
                if (userBal == totalNeed) game.allInPlayers.add(userId);
                actionAccepted = true;
                sendMessage(chatId, "🚀 " + getMention(userId, user.getFirstName()) + " đã Tố thêm " + formatDetailedMoney(param) + " đ.! ");
            } else if (action.equals("allin")) {
                if (param != 100) {
                    if (queryId != null) answerAlert(queryId, "❌ TẤT TAY là 100% số dư.");
                    return;
                }
                if (userBal <= 0) {
                    if (queryId != null) answerAlert(queryId, "❌ Số dư của bạn đã hết, không thể cược thêm.");
                    return;
                }
                if (!Database.tryChangeBalance(userId, -userBal)) {
                    if (queryId != null) answerAlert(queryId, "❌ Số dư vừa thay đổi, vui lòng thử lại.");
                    return;
                }
                long newBet = userCurrentBet + userBal;
                game.playerBets.put(userId, newBet);
                game.totalPot += userBal;
                game.allInPlayers.add(userId);
                game.actedInCurrentRound.add(userId);

                if (newBet > game.highestBet) {
                    game.highestBet = newBet;
                    game.actedInCurrentRound.clear();
                    game.actedInCurrentRound.add(userId);
                }
                actionAccepted = true;
                sendMessage(chatId, "🔥 " + getMention(userId, user.getFirstName()) + " đã TẤT TAY " + formatDetailedMoney(userBal) + " đ.! ");
            }

            if (!actionAccepted) return;

            if (queryId != null) answerAlert(queryId, "✅ Đã thực hiện!");
            if (game.turnCountdown != null) game.turnCountdown.cancel(true);

            if (shouldEndBaiCaoRound(game)) {
                endBaiCaoGame(chatId, getActiveBaiCaoPlayers(game));
            } else {
                advanceBaiCaoTurn(chatId);
            }
        }
    }

    private List<Long> getActiveBaiCaoPlayers(BaiCaoGame game) {
        List<Long> active = new ArrayList<>();
        for (long pId : game.playerOrder) {
            if (!game.folded.contains(pId)) active.add(pId);
        }
        return active;
    }

    private boolean isActionableBaiCaoPlayer(BaiCaoGame game, long pId) {
        return !game.folded.contains(pId) && !game.allInPlayers.contains(pId);
    }

    private boolean shouldEndBaiCaoRound(BaiCaoGame game) {
        List<Long> active = getActiveBaiCaoPlayers(game);
        if (active.size() <= 1) return true;

        boolean hasActionable = false;
        for (long pId : active) {
            if (!game.allInPlayers.contains(pId)) {
                hasActionable = true;
                long bet = game.playerBets.getOrDefault(pId, 0L);
                if (bet != game.highestBet) return false;
                if (!game.actedInCurrentRound.contains(pId)) return false;
            }
        }
        return !hasActionable || allActionablePlayersMatchedAndActed(game);
    }

    private boolean allActionablePlayersMatchedAndActed(BaiCaoGame game) {
        for (long pId : getActiveBaiCaoPlayers(game)) {
            if (game.allInPlayers.contains(pId)) continue;
            if (game.playerBets.getOrDefault(pId, 0L) != game.highestBet) return false;
            if (!game.actedInCurrentRound.contains(pId)) return false;
        }
        return true;
    }

    private String percentText(long p) {
        return p + "%";
    }

    private void handleBaiCaoTimeout(long chatId, long userId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || !game.playing) return;

        long currentUserId = game.playerOrder.get(game.currentTurnIndex);
        if (currentUserId == userId) {
            game.folded.add(userId);
            sendMessage(chatId, "⏰ <b>Quá 50 giây không thao tác, người chơi " + getMention(userId, game.players.get(userId)) + " đã tự động úp bài!</b>");
            
            List<Long> activePlayers = new ArrayList<>();
            for (long pId : game.playerOrder) {
                if (!game.folded.contains(pId)) activePlayers.add(pId);
            }
            if (activePlayers.size() <= 1) {
                if (game.turnCountdown != null) game.turnCountdown.cancel(true);
                if (game.lastTagMessageId != null) deleteMessage(chatId, game.lastTagMessageId);
                endBaiCaoGame(chatId, activePlayers);
                return;
            }

            advanceBaiCaoTurn(chatId);
        }
    }

    private void advanceBaiCaoTurn(long chatId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || !game.playing) return;

        synchronized (game) {
            List<Long> activePlayers = getActiveBaiCaoPlayers(game);
            if (activePlayers.size() <= 1 || shouldEndBaiCaoRound(game)) {
                endBaiCaoGame(chatId, activePlayers);
                return;
            }

            int attempts = 0;
            do {
                game.currentTurnIndex = (game.currentTurnIndex + 1) % game.playerOrder.size();
                attempts++;
            } while (!isActionableBaiCaoPlayer(game, game.playerOrder.get(game.currentTurnIndex))
                    && attempts <= game.playerOrder.size());

            if (attempts > game.playerOrder.size()) {
                endBaiCaoGame(chatId, activePlayers);
                return;
            }

            sendBaiCaoPlayingMessage(chatId);
        }
    }

    private void endBaiCaoGame(long chatId, List<Long> activePlayers) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null) return;

        synchronized (game) {
            if (baicaoGames.get(chatId) != game) return;
            if (game.turnCountdown != null) game.turnCountdown.cancel(true);

            game.playing = false;

            Map<Long, Long> winnings = new HashMap<>();
            List<Long> contributors = new ArrayList<>();
            for (long pId : game.playerOrder) {
                if (game.playerBets.getOrDefault(pId, 0L) > 0) contributors.add(pId);
            }
            contributors.sort(Comparator.comparingLong(p -> game.playerBets.getOrDefault(p, 0L)));

            long previousTier = 0;
            long distributed = 0;
            long returnedUncalled = 0;

            List<Long> tiers = new ArrayList<>();
            for (long pId : contributors) {
                long c = game.playerBets.getOrDefault(pId, 0L);
                if (c > previousTier && !tiers.contains(c)) tiers.add(c);
            }
            Collections.sort(tiers);

            for (long tier : tiers) {
                long layer = tier - previousTier;
                if (layer <= 0) continue;

                List<Long> layerContributors = new ArrayList<>();
                List<Long> eligible = new ArrayList<>();
                for (long pId : contributors) {
                    long c = game.playerBets.getOrDefault(pId, 0L);
                    if (c >= tier) {
                        layerContributors.add(pId);
                        if (!game.folded.contains(pId)) eligible.add(pId);
                    }
                }

                long potSlice = layer * layerContributors.size();
                if (potSlice <= 0) {
                    previousTier = tier;
                    continue;
                }

                if (eligible.isEmpty()) {
                    List<Long> fallbackEligible = new ArrayList<>();
                    for (long pId : game.playerOrder) {
                        if (!game.folded.contains(pId) && game.playerBets.getOrDefault(pId, 0L) > 0) {
                            fallbackEligible.add(pId);
                        }
                    }

                    if (!fallbackEligible.isEmpty()) {
                        List<Long> winners = findBestHandWinners(game, fallbackEligible);
                        splitAmount(winnings, winners, potSlice);
                        distributed += potSlice;
                    }
                } else {
                    List<Long> winners = findBestHandWinners(game, eligible);
                    splitAmount(winnings, winners, potSlice);
                    distributed += potSlice;
                }
                previousTier = tier;
            }

            long payoutTotal = 0;
            for (long amount : winnings.values()) payoutTotal += amount;

            long expected = game.totalPot;
            if (payoutTotal < expected && !activePlayers.isEmpty()) {
                List<Long> winners = findBestHandWinners(game, activePlayers);
                if (!winners.isEmpty()) {
                    splitAmount(winnings, winners, expected - payoutTotal);
                    payoutTotal = expected;
                }
            }

            for (Map.Entry<Long, Long> entry : winnings.entrySet()) {
                if (entry.getValue() > 0) Database.changeBalance(entry.getKey(), entry.getValue());
            }

            long absoluteWinnerId = activePlayers.isEmpty() ? game.playerOrder.get(0) : findBestHandWinners(game, activePlayers).get(0);
            HandScore maxSc = evaluateHand(game.cards.get(absoluteWinnerId));

            StringBuilder winMsg = new StringBuilder();
            winMsg.append("🏆 <b>KẾT THÚC VÁN BÀI TỐ</b>\n\n");
            winMsg.append("👑 <b>Người chiến thắng:</b> ").append(getMention(absoluteWinnerId, game.players.get(absoluteWinnerId))).append("\n");
            winMsg.append("💰 <b>Tổng hũ:</b> ").append(formatDetailedMoney(game.totalPot)).append(" đ.\n");
            if (returnedUncalled > 0) {
                winMsg.append("↩️ <b>Tiền cược thừa đã hoàn lại:</b> ").append(formatDetailedMoney(returnedUncalled)).append(" đ.\n");
            }
            winMsg.append("\n📋 <b>Bài và kết quả:</b>");

            for (long pId : game.playerOrder) {
                List<Card> h = game.cards.get(pId);
                if (h == null) continue;
                HandScore sc = evaluateHand(h);
                String statusNote = game.folded.contains(pId) ? " (Đã úp bài)" : " — <b>" + sc.description + "</b>";
                winMsg.append("\n- ").append(getMention(pId, game.players.get(pId)))
                      .append(": [ ").append(h.get(0)).append(" ").append(h.get(1)).append(" ").append(h.get(2)).append(" ]")
                      .append(statusNote);
            }

            if (game.messageId != null) deleteMessage(chatId, game.messageId);
            if (game.lastTagMessageId != null) deleteMessage(chatId, game.lastTagMessageId);
            sendMessage(chatId, winMsg.toString());
            baicaoGames.remove(chatId, game);
            sendMainMenu(chatId);
        }
    }

    private List<Long> findBestHandWinners(BaiCaoGame game, List<Long> eligible) {
        List<Long> winners = new ArrayList<>();
        if (eligible == null || eligible.isEmpty()) return winners;
        HandScore best = null;
        for (long pId : eligible) {
            HandScore sc = evaluateHand(game.cards.get(pId));
            if (best == null || sc.compareTo(best) > 0) {
                best = sc;
                winners.clear();
                winners.add(pId);
            } else if (sc.compareTo(best) == 0) {
                winners.add(pId);
            }
        }
        return winners;
    }

    private void splitAmount(Map<Long, Long> winnings, List<Long> winners, long amount) {
        if (amount <= 0 || winners == null || winners.isEmpty()) return;
        long each = amount / winners.size();
        long remainder = amount % winners.size();
        for (int i = 0; i < winners.size(); i++) {
            long pay = each + (i < remainder ? 1 : 0);
            winnings.put(winners.get(i), winnings.getOrDefault(winners.get(i), 0L) + pay);
        }
    }

    private HandScore evaluateHand(List<Card> hand) {
        List<Card> sorted = new ArrayList<>(hand);
        Collections.sort(sorted);
        Card c1 = sorted.get(0);
        Card c2 = sorted.get(1);
        Card c3 = sorted.get(2);

        if (c1.rankValue == c2.rankValue && c2.rankValue == c3.rankValue) {
            return new HandScore(4, c1.rankValue, 0, "Sáp " + c1.rank);
        }

        boolean isLieng = false;
        int liengHighValue = c3.rankValue;
        if (c1.rankValue + 1 == c2.rankValue && c2.rankValue + 1 == c3.rankValue) {
            isLieng = true;
        } else if (c1.rankValue == 2 && c2.rankValue == 3 && c3.rankValue == 14) {
            isLieng = true;
            liengHighValue = 3;
        }

        if (isLieng) {
            return new HandScore(3, liengHighValue, c3.suitValue, "Liêng");
        }

        boolean isAllFaceCards = c1.rankValue >= 11 && c1.rankValue <= 13 &&
                                 c2.rankValue >= 11 && c2.rankValue <= 13 &&
                                 c3.rankValue >= 11 && c3.rankValue <= 13;
        if (isAllFaceCards) {
            return new HandScore(2, c3.rankValue, c3.suitValue, "3 Tây");
        }

        int totalPoints = 0;
        for (Card c : sorted) {
            int p = c.rankValue;
            if (p >= 10 && p <= 13) p = 10;
            else if (p == 14) p = 1;
            totalPoints += p;
        }
        int score = totalPoints % 10;
        return new HandScore(1, score, c3.suitValue, score + " điểm");
    }

    private void takeDealer(long chatId, User user, String queryId) {
        DiceGame game = games.get(chatId);
        if (game == null) {
            answerAlert(queryId, "⚠️ Ván đấu không tồn tại!");
            return;
        }

        long bal = Database.getBalance(user.getId());
        if (bal < 10_000_000L) {
            answerAlert(queryId, "👉 Số dư không đủ điều kiện cầm cái! Yêu cầu tối thiểu 10M.");
            return;
        }

        if (game.dealerId == null) {
            // Lần đầu có người bấm -> Giữ tạm thời và bắt đầu đếm ngược 7 giây tranh cái
            game.dealerId = user.getId();
            game.dealerName = user.getFirstName();
            game.dealerBalance = bal;
            game.dealerClaimingWindow = true;

            answerAlert(queryId, "👑 Bạn đã tạm giữ cái! Đang chờ 7 giây tranh quyền...");
            updateGameMessage(chatId);

            if (game.dealerClaimTask != null) game.dealerClaimTask.cancel(true);
            game.dealerClaimTask = scheduler.schedule(() -> finalizeDealer(chatId), 7, TimeUnit.SECONDS);
        } else if (game.dealerClaimingWindow) {
            // Trong thời gian tranh cái, nếu ai có số dư cao hơn thì thay thế, không thông báo rườm rà
            if (user.getId().equals(game.dealerId)) {
                answerAlert(queryId, "⚠️ Bạn đang giữ cái tạm thời rồi!");
                return;
            }

            if (bal > game.dealerBalance) {
                game.dealerId = user.getId();
                game.dealerName = user.getFirstName();
                game.dealerBalance = bal;
                answerAlert(queryId, "👑 Bạn đã tranh giữ cái thành công nhờ số dư lớn hơn!");
                updateGameMessage(chatId);
            } else {
                answerAlert(queryId, "❌ Số dư của bạn thấp hơn người đang giữ cái (" + formatDetailedMoney(game.dealerBalance) + " đ.).");
            }
        } else {
            answerAlert(queryId, "⚠️ Đã chốt nhà cái, không thể tranh nữa!");
        }
    }

    private void finalizeDealer(long chatId) {
        DiceGame game = games.get(chatId);
        if (game == null) return;

        synchronized (game) {
            game.dealerClaimingWindow = false;
            game.startTime = System.currentTimeMillis();
            
            // Sau khi hết 7s, cập nhật lại tin nhắn bàn thành menu cược chính thức
            updateGameMessage(chatId);

            if (game.countdownTask != null) game.countdownTask.cancel(true);
            game.countdownTask = scheduler.scheduleAtFixedRate(() -> updateDiceGameTimer(chatId), 5, 5, TimeUnit.SECONDS);
            
            sendMessage(chatId, "👑 <b>Đã chốt nhà cái:</b> " + game.dealerName + " (Số dư: " + formatDetailedMoney(game.dealerBalance) + " đ.)\n🎯 Mời các người chơi đặt cược trong 40 giây!");
        }
    }

    private void placeBet(long chatId, User user, String side, long amount, String queryId) {
        DiceGame game = games.get(chatId);
        if (game == null || game.rolling) return;

        if (game.dealerId == null || game.dealerClaimingWindow) {
            answerAlert(queryId, "⚠️ Ván đấu đang trong thời gian tranh cái, chưa thể đặt cược!");
            return;
        }

        if (game.dealerId.equals(user.getId())) {
            answerAlert(queryId, "⚠️ Cầm cái không được cược.");
            return;
        }

        if (game.bets.containsKey(user.getId())) {
            answerAlert(queryId, "👉 Bạn chỉ được cược 1 lần mỗi ván!");
            return;
        }

        long userBal = Database.getBalance(user.getId());
        if (userBal < amount) {
            answerAlert(queryId, "❌ Số dư của bạn không đủ để đặt cược " + formatDetailedMoney(amount) + " đ.!");
            return;
        }

        long currentTotalBets = 0;
        for (Bet b : game.bets.values()) {
            currentTotalBets += b.amount;
        }
        long dealerBal = Database.getBalance(game.dealerId);
        if (dealerBal < (currentTotalBets + amount)) {
            answerAlert(queryId, "❌ Nhà cái không đủ số dư để nhận mức cược này!");
            return;
        }

        Database.changeBalance(user.getId(), -amount);
        game.bets.put(user.getId(), new Bet(user.getId(), user.getFirstName(), side, amount));

        String sideName = side.equals("T") ? "Tài" : "Xỉu";
        answerAlert(queryId, "👉 Đặt cược " + sideName + " " + formatDetailedMoney(amount) + " đ. thành công!");
        updateGameMessage(chatId);
    }

    private void rollDiceManual(long chatId, User user, String queryId) {
        DiceGame game = games.get(chatId);
        if (game == null || game.dealerClaimingWindow || !user.getId().equals(game.dealerId) || game.rolling) return;
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

        if (game.messageId != null) {
            deleteMessage(chatId, game.messageId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🎲 <b>BẮT ĐẦU LẮC XÚC XẮC</b>\n\n");
        sb.append("👑 <b>Cầm cái:</b> ").append(game.dealerName).append("\n");
        if (!game.bets.isEmpty()) {
            sb.append("\n📋 <b>Danh sách cược:</b>\n");
            int idx = 1;
            for (Bet b : game.bets.values()) {
                String icon = b.side.equals("T") ? "🔴" : "🔵";
                String sideName = b.side.equals("T") ? "Tài" : "Xỉu";
                sb.append(idx).append("- ").append(icon).append(" ").append(getMention(b.userId, b.name))
                  .append(" → ").append(sideName).append(": ").append(formatDetailedMoney(b.amount)).append(" đ.\n");
                idx++;
            }
        }
        sb.append("\n<i>Đang gieo xúc xắc... Vui lòng đợi!</i>");
        sendMessageSync(chatId, sb.toString());

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
                settleGame(chatId, total, result, null);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void settleGame(long chatId, int total, String result, Integer oldMessageId) {
        DiceGame game = games.remove(chatId);
        if (game == null) return;
        if (game.countdownTask != null) game.countdownTask.cancel(true);
        if (game.dealerClaimTask != null) game.dealerClaimTask.cancel(true);

        if (oldMessageId != null) {
            deleteMessage(chatId, oldMessageId);
        }

        diceHistory.add(result.equals("T") ? "🔴" : "🔵");

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
                sb.append("✅ ").append(getMention(b.userId, b.name)).append(" thắng ").append(formatDetailedMoney(b.amount)).append(" đ.\n");
            } else {
                dealerProfit += b.amount;
                sb.append("❌ ").append(getMention(b.userId, b.name)).append(" thua ").append(formatDetailedMoney(b.amount)).append(" đ.\n");
            }
        }

        if (game.dealerId != null) {
            Database.changeBalance(game.dealerId, dealerProfit);
            sb.append("\n👑 <b>Cầm cái:</b> ").append(dealerProfit >= 0 ? "+" : "").append(formatDetailedMoney(dealerProfit)).append(" đ.");
        }

        long lixiAmount = (long)(totalBetsSum * 0.006);
        InlineKeyboardMarkup lixiMarkup = null;
        if (lixiAmount > 0) {
            lixiSessions.put(chatId, new LixiSession(lixiAmount, sb.toString()));
            lixiMarkup = new InlineKeyboardMarkup(List.of(List.of(createBtn("🧧 NHẬN LÌ XÌ (" + formatDetailedMoney(lixiAmount) + " đ.) 🧧", "claim_lixi"))));
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

            answerAlert(queryId, "🎉 Bạn trúng lì xì " + formatDetailedMoney(session.amount) + " đ.!");
            String updateText = session.baseText + "\n\n🧧 <b>LÌ XÌ:</b> 🎉 Chúc mừng " + getMention(user.getId(), user.getFirstName()) + " đã nhặt thành công " + formatDetailedMoney(session.amount) + " đ.!";

            EditMessageText edit = new EditMessageText();
            edit.setChatId(String.valueOf(chatId));
            if (message != null) {
                edit.setMessageId(message.getMessageId());
            }
            edit.setText(updateText);
            edit.setParseMode("HTML");
            telegramExecutor.execute(() -> { try { execute(edit); } catch (Exception ignored) {} });
        } else {
            answerAlert(queryId, "❌ Trượt rồi! Chúc bạn may mắn lần sau (" + session.clickCount + "/5).");
        }
    }

    private boolean isUserInAnyActiveGame(long userId) {
        for (DiceGame game : games.values()) {
            if (game != null && (game.rolling || game.dealerId != null && game.dealerId == userId || game.bets.containsKey(userId))) {
                return true;
            }
        }
        for (BaiCaoGame game : baicaoGames.values()) {
            if (game != null && game.players.containsKey(userId)) return true;
        }
        return false;
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

        if (isUserInAnyActiveGame(senderId)) {
            sendMessage(message.getChatId(), "❌ Bạn đang chơi/cược trong một ván, không thể chuyển điểm lúc này.");
            return;
        }
        if (isUserInAnyActiveGame(receiverId)) {
            sendMessage(message.getChatId(), "❌ Người nhận đang chơi/cược trong một ván, không thể nhận chuyển điểm lúc này.");
            return;
        }

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

        if (!isAdmin) {
            if (!Database.tryChangeBalance(senderId, -amount)) {
                sendMessage(message.getChatId(), "❌ Số dư không đủ hoặc vừa thay đổi!");
                return;
            }
        }
        Database.changeBalance(receiverId, amount);

        sendMessage(message.getChatId(), "💸 Chuyển thành công " + formatDetailedMoney(amount) + " đ. cho " + getMention(receiverId, message.getReplyToMessage().getFrom().getFirstName()) + "!");
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
        sendMessage(message.getChatId(), "🔻 Đã trừ " + formatDetailedMoney(amount) + " đ. của " + getMention(targetId, message.getReplyToMessage().getFrom().getFirstName()));
    }

    private void sendMainMenu(long chatId) {
        Integer oldMenuId = lastMenuMessageIds.remove(chatId);
        if (oldMenuId != null) {
            deleteMessage(chatId, oldMenuId);
        }

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText("🎰 <b>HỆ THỐNG GAME</b>\n\nChọn game muốn chơi bên dưới:");
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

        telegramExecutor.execute(() -> { 
            try { 
                Message sent = execute(msg); 
                lastMenuMessageIds.put(chatId, sent.getMessageId());
            } catch (Exception ignored) {} 
        });
    }

    private void updateGameMessage(long chatId) {
        DiceGame game = games.get(chatId);
        if (game == null || game.messageId == null) return;

        long elapsed = (System.currentTimeMillis() - game.startTime) / 1000;
        int remaining = (int) (40 - elapsed);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(game.messageId);
        edit.setText(getDiceGameText(game, remaining));
        edit.setParseMode("HTML");
        // Nếu đang trong cửa sổ tranh cái thì chỉ hiển thị nút Cầm Cái, ngược lại hiện menu cược
        edit.setReplyMarkup((game.dealerId == null || game.dealerClaimingWindow) ? getDealerKeyboard() : getBettingKeyboard());

        telegramExecutor.execute(() -> { try { execute(edit); } catch (Exception ignored) {} });
    }

    private InlineKeyboardMarkup getDealerKeyboard() {
        return new InlineKeyboardMarkup(List.of(List.of(createBtn("👉 CẦM CÁI 👈", "take_dealer"))));
    }

    private InlineKeyboardMarkup getBettingKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (long amt : BET_AMOUNTS) {
            rows.add(List.of(
                    createBtn("🔴 Tài " + formatShortMoney(amt), "bet:T:" + amt),
                    createBtn("🔵 Xỉu " + formatShortMoney(amt), "bet:X:" + amt)
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

    private void sendMessageSync(long chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        msg.setParseMode("HTML");
        telegramExecutor.execute(() -> { try { execute(msg); } catch (Exception ignored) {} });
    }

    private void sendMessageWithMarkup(long chatId, String text, InlineKeyboardMarkup markup) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        msg.setParseMode("HTML");
        if (markup != null) msg.setReplyMarkup(markup);
        telegramExecutor.execute(() -> {
            try { execute(msg); } catch (Exception ignored) {}
        });
    }

    private void deleteMessage(long chatId, int messageId) {
        telegramExecutor.execute(() -> {
            try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception ignored) {}
        });
    }

    private void answerAlert(String queryId, String text) {
        AnswerCallbackQuery ans = new AnswerCallbackQuery();
        ans.setCallbackQueryId(queryId);
        ans.setText(text);
        ans.setShowAlert(true);
        callbackExecutor.execute(() -> {
            try { execute(ans); } catch (Exception ignored) {}
        });
    }

    private String formatDetailedMoney(long amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.getDefault());
        symbols.setGroupingSeparator(',');
        DecimalFormat formatter = new DecimalFormat("#,###", symbols);
        return formatter.format(amount);
    }

    private String formatShortMoney(long amount) {
        if (amount >= 1_000_000_000L) {
            long b = amount / 1_000_000_000L;
            long remainder = (amount % 1_000_000_000L) / 100_000_000L;
            return remainder > 0 ? b + "." + remainder + "B" : b + "B";
        } else if (amount >= 1_000_000L) {
            long m = amount / 1_000_000L;
            long remainder = (amount % 1_000_000L) / 100_000L;
            return remainder > 0 ? m + "." + remainder + "M" : m + "M";
        } else if (amount >= 1_000L) {
            return (amount / 1_000L) + "K";
        }
        return String.valueOf(amount);
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

    public void shutdownExecutors() {
        scheduler.shutdownNow();
        telegramExecutor.shutdownNow();
        callbackExecutor.shutdownNow();
        updateExecutor.shutdownNow();
        priorityExecutor.shutdownNow();
    }
}
