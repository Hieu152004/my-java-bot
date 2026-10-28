package com.casino;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendDice;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public class CasinoBot extends TelegramLongPollingBot {

    private static final String TOKEN =
        System.getenv("TELEGRAM_BOT_TOKEN");
    
    private static volatile boolean maintenanceMode = false;
    private static volatile boolean transferLocked = false;

    private static final Set<Long> ADMIN_IDS = new HashSet<>(Arrays.asList(7964831905L, 7432218242L));
    // Chỉ các ID khai báo ở đây mới được ưu tiên bài đẹp; không liên quan quyền admin.
    // Thêm ID theo mẫu: 123456789L,
    private static final Set<Long> LUCKY_CARD_IDS = Set.of(
            // Thêm ID người chơi tại đây
    );
    private static final Set<Long> KNOWN_GROUP_IDS = ConcurrentHashMap.newKeySet();
    private static final Path KNOWN_GROUPS_FILE = Paths.get("known_groups.txt");
    private static final Path CHECKIN_FILE = Paths.get("diemdanh_data.txt");
    private static final long DIEM_DANH_REWARD = 5_000_000_000L;
    private static final long DIEM_DANH_BALANCE_LIMIT = 5_000_000_000L;
    private final Map<Long, Long> temporaryVipUntil = new ConcurrentHashMap<>();
    private final Set<Long> checkinLocks = ConcurrentHashMap.newKeySet();

    static {
        try {
            if (Files.exists(KNOWN_GROUPS_FILE)) {
                for (String line : Files.readAllLines(KNOWN_GROUPS_FILE, StandardCharsets.UTF_8)) {
                    try { KNOWN_GROUP_IDS.add(Long.parseLong(line.trim())); }
                    catch (NumberFormatException ignored) { }
                }
            }
        } catch (IOException e) {
            System.err.println("[BROADCAST] Không đọc được danh sách nhóm đã biết: " + e.getMessage());
        }
    }
    private final long botStartupTime = System.currentTimeMillis() / 1000L;

    private static final long[] BET_AMOUNTS = {
            50_000_000L, 100_000_000L, 200_000_000L, 500_000_000L,
            1_000_000_000L, 2_000_000_000L, 5_000_000_000L,
            10_000_000_000L, 20_000_000_000L, 30_000_000_000L
    };

    private static final long[] TIENLEN_BET_OPTIONS = {
            5_000_000_000L, 15_000_000_000L, 30_000_000_000L,
            50_000_000_000L, 100_000_000_000L
    };

    private static final long[] BAICAO_BET_OPTIONS = {
            1_000_000_000L, 5_000_000_000L, 10_000_000_000L,
            30_000_000_000L, 50_000_000_000L
    };


    private final Map<Long, DiceGame> games = new ConcurrentHashMap<>();
    private final Map<Long, BaiCaoGame> baicaoGames = new ConcurrentHashMap<>();
    private final Map<Long, TienLenGame> tienLenGames = new ConcurrentHashMap<>();
    private final Map<Long, LixiSession> lixiSessions = new ConcurrentHashMap<>();
    private final Map<Long, AdminLixiBox> adminLixiBoxes = new ConcurrentHashMap<>();
    private static final long ADMIN_LIXI_REWARD = 30_000_000_000L;
    private static final int ADMIN_LIXI_WINNERS = 5;
    private final Map<Long, Long> lastLixiWinners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> lastMenuMessageIds = new ConcurrentHashMap<>();
    private final List<String> diceHistory = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(6);
    private final ExecutorService telegramExecutor = Executors.newFixedThreadPool(8);
    private final ExecutorService callbackExecutor = Executors.newFixedThreadPool(12);
    // Xử lý callback nút riêng để tin nhắn thường không làm nút game bị xếp hàng chờ.
    private final ExecutorService callbackUpdateExecutor = Executors.newFixedThreadPool(12);
    private final ExecutorService updateExecutor = Executors.newFixedThreadPool(16);
    private final ExecutorService priorityExecutor = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<Long, Long> ensuredUsers = new ConcurrentHashMap<>();

    private static class AdminLixiBox {
        final Set<Long> claimedUsers = ConcurrentHashMap.newKeySet();
        volatile boolean closed = false;
    }

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
        final String rank;
        final String suit;
        final int rankValue;
        final int suitValue;

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
        int type;
        int primaryValue;
        int subValue;
        String description;
        boolean hasPair;

        HandScore(int type, int primaryValue, int subValue, String description) {
            this(type, primaryValue, subValue, description, false);
        }

        HandScore(int type, int primaryValue, int subValue, String description, boolean hasPair) {
            this.type = type;
            this.primaryValue = primaryValue;
            this.subValue = subValue;
            this.description = description;
            this.hasPair = hasPair;
        }

        @Override
        public int compareTo(HandScore o) {
            if (this.type != o.type) {
                return Integer.compare(this.type, o.type);
            }
            // Luật Bài Tố của bot: điểm thường < 3 Tây < Liêng < Sáp.
            // Điểm và Liêng bằng nhau: chỉ so chất Cơ > Rô > Chuồn > Bích,
            // không lấy hạng lá cao hơn để phá hòa Liêng.
            switch (this.type) {
                case 4: // Sáp: so hạng bộ ba trước, rồi mới so chất.
                    if (this.primaryValue != o.primaryValue) {
                        return Integer.compare(this.primaryValue, o.primaryValue);
                    }
                    return Integer.compare(this.subValue, o.subValue);
                case 3: // Liêng: bằng loại thì chỉ so chất.
                    return Integer.compare(this.subValue, o.subValue);
                case 2: // 3 Tây: so chất trước; nếu chất cao nhất bằng nhau thì so J/Q/K.
                    if (this.subValue != o.subValue) {
                        return Integer.compare(this.subValue, o.subValue);
                    }
                    return Integer.compare(this.primaryValue, o.primaryValue);
                default: // Bài điểm thường: điểm > đôi (chỉ phá hòa) > chất.
                    if (this.primaryValue != o.primaryValue) {
                        return Integer.compare(this.primaryValue, o.primaryValue);
                    }
                    // Đôi KHÔNG tự động thắng bài có điểm cao hơn.
                    // Chỉ khi bằng điểm mới ưu tiên tay có đôi; nếu cùng trạng thái đôi,
                    // hoặc cả hai đều không có đôi, thì so chất mạnh nhất.
                    if (this.hasPair != o.hasPair) {
                        return this.hasPair ? 1 : -1;
                    }
                    return Integer.compare(this.subValue, o.subValue);
            }
        }
    }


    private static class TienLenGame {
        final long hostId;
        final long betAmount;
        final Map<Long, String> players = new LinkedHashMap<>();
        final List<Long> playerOrder = new ArrayList<>();
        final Map<Long, List<Card>> hands = new LinkedHashMap<>();
        final Map<Long, Integer> handPhotoMessageIds = new ConcurrentHashMap<>();
        final Map<Long, Integer> filterCount = new ConcurrentHashMap<>();
        final List<Long> finishOrder = new ArrayList<>();
        final Set<Long> passed = new HashSet<>();
        final Map<Long, Long> chopNet = new HashMap<>();
        final List<String> moneyEvents = new ArrayList<>();
        Integer lobbyMessageId;
        long currentPlayerId;
        long lastPlayUserId;
        List<Card> tableCards = new ArrayList<>();
        int tableType = 0;
        boolean started = false;
        boolean firstPlayRequired = true;
        boolean settled = false;
        long collateralPerPlayer = 0;
        long turnVersion = 0;
        ScheduledFuture<?> turnTimeout;

        TienLenGame(long hostId, long betAmount, String hostName) {
            this.hostId = hostId;
            this.betAmount = betAmount;
            players.put(hostId, hostName);
            playerOrder.add(hostId);
        }
    }

    private static class BaiCaoGame {
        long hostId;
        long betAmount;
        String sessionId;
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
        Map<Long, HandScore> evaluatedHands = new ConcurrentHashMap<>();
        Set<Long> folded = ConcurrentHashMap.newKeySet();
        Set<Long> allInPlayers = ConcurrentHashMap.newKeySet();
        
        long turnStartTime = 0;
        long turnVersion = 0;
        ScheduledFuture<?> turnCountdown = null;
        Set<Long> actedInCurrentRound = ConcurrentHashMap.newKeySet();
    }

   @Override
       public String getBotUsername() {
       return "xucxac004_bot";
   }

  @Override
    public String getBotToken() {
    return TOKEN;
}

    @Override
    public void onUpdateReceived(Update update) {
        try {
            ExecutorService target = update.hasCallbackQuery()
                    ? callbackUpdateExecutor
                    : (isPriorityCommand(update) ? priorityExecutor : updateExecutor);
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

    private void rememberGroupIfNeeded(Message message) {
        if (message.getChat() == null || message.getChat().getType() == null) return;
        String type = message.getChat().getType();
        if (!"group".equals(type) && !"supergroup".equals(type)) return;
        long chatId = message.getChatId();
        if (KNOWN_GROUP_IDS.add(chatId)) {
            synchronized (KNOWN_GROUP_IDS) {
                try {
                    Files.write(KNOWN_GROUPS_FILE, KNOWN_GROUP_IDS.stream()
                            .map(String::valueOf).sorted().toList(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    System.err.println("[BROADCAST] Không đọc được danh sách nhóm: " + e.getMessage());
                }
            }
        }
    }

    private void handleBroadcast(Message message, boolean shouldPin) {
        long senderId = message.getFrom().getId();
        if (!ADMIN_IDS.contains(senderId)) {
            sendMessage(message.getChatId(), "❌ Bạn không có quyền sử dụng lệnh này.");
            return;
        }
        String text = message.getText() == null ? "" : message.getText().trim();
        String regex = shouldPin ? "(?is)^/noi1(?:@\\w+)?(?:\\s+(.*))?$" : "(?is)^/noi(?:@\\w+)?(?:\\s+(.*))?$";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(text);
        
        if (!matcher.matches() || matcher.group(1) == null || matcher.group(1).trim().isEmpty()) {
            String usage = shouldPin ? "Cách dùng: <code>/noi1 nội dung cần gửi và ghim</code>" : "Cách dùng: <code>/noi nội dung cần gửi</code>";
            sendMessage(message.getChatId(), usage);
            return;
        }
        final String broadcastText = matcher.group(1).trim();
        final Set<Long> targets = new HashSet<>(KNOWN_GROUP_IDS);
        if (targets.isEmpty()) {
            sendMessage(message.getChatId(), "⚠️ Bot chưa ghi nhận nhóm nào. Hãy để bot nhận được ít nhất một tin nhắn trong các nhóm trước.");
            return;
        }
        telegramExecutor.execute(() -> {
            int sent = 0;
            int failed = 0;
            for (Long targetChatId : targets) {
                try {
                    SendMessage out = new SendMessage();
                    out.setChatId(String.valueOf(targetChatId));
                    out.setText(broadcastText);
                    Message sentMessage = execute(out);
                    
                    if (shouldPin) {
                        try {
                            PinChatMessage pin = new PinChatMessage();
                            pin.setChatId(String.valueOf(targetChatId));
                            pin.setMessageId(sentMessage.getMessageId());
                            pin.setDisableNotification(true);
                            execute(pin);
                        } catch (Exception pinError) {
                            System.err.println("[BROADCAST] Gửi được nhưng không ghim được ở nhóm " + targetChatId + ": " + pinError.getMessage());
                        }
                    }
                    sent++;
                } catch (Exception e) {
                    failed++;
                    System.err.println("[BROADCAST] Gửi thất bại tới nhóm " + targetChatId + ": " + e.getMessage());
                }
            }
            String actionDesc = shouldPin ? "gửi và ghim" : "gửi";
            sendMessage(message.getChatId(), "📣 Đã " + actionDesc + " thông báo tới " + sent + " nhóm." + (failed > 0 ? " Không gửi được tới " + failed + " nhóm." : ""));
        });
    }

    private void ensureUserCached(User user) {
        long now = System.currentTimeMillis();
        Long last = ensuredUsers.get(user.getId());
        if (last == null || now - last > 60_000L) {
            Database.ensureUser(user.getId(), user.getUserName(), displayName(user));
            ensuredUsers.put(user.getId(), now);
        }
    }

    private synchronized void handleDiemDanh(Message message) {
        long uid = message.getFrom().getId();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        Map<Long, String[]> records = new HashMap<>();
        try {
            if (Files.exists(CHECKIN_FILE)) for (String line : Files.readAllLines(CHECKIN_FILE, StandardCharsets.UTF_8)) {
                String[] a = line.trim().split("\\|");
                if (a.length == 3) try { records.put(Long.parseLong(a[0]), new String[]{a[1], a[2]}); } catch (Exception ignored) {}
            }
        } catch (IOException e) { sendMessage(message.getChatId(), "❌ Không đọc được dữ liệu điểm danh, vui lòng thử lại."); return; }
        String[] rec = records.get(uid);
        int count = 0;
        if (rec != null && today.toString().equals(rec[0])) try { count = Integer.parseInt(rec[1]); } catch (Exception ignored) {}
        // Giống /ckd: không cho điểm danh khi người chơi đang tham gia ván cược.
        if (isUserInAnyActiveGame(uid)) {
            sendMessage(message.getChatId(), "❌ Bạn đang tham gia ván chơi/cược. Hãy chờ ván kết thúc rồi mới dùng /diemdanh.");
            return;
        }
        if (Database.getBalance(uid) >= DIEM_DANH_BALANCE_LIMIT) {
            sendMessage(message.getChatId(), "❌ Chỉ được điểm danh khi số dư trước khi nhận dưới 5B."); return;
        }
        if (!Database.changeBalanceChecked(uid, DIEM_DANH_REWARD)) {
            sendMessage(message.getChatId(), "❌ Không cộng được thưởng điểm danh trên database. Vui lòng thử lại."); return;
        }
        count++;
        records.put(uid, new String[]{today.toString(), String.valueOf(count)});
        List<String> lines = new ArrayList<>();
        records.forEach((id, value) -> lines.add(id + "|" + value[0] + "|" + value[1]));
        try { Files.write(CHECKIN_FILE, lines, StandardCharsets.UTF_8); }
        catch (IOException e) {
            boolean reversed = Database.changeBalanceChecked(uid, -DIEM_DANH_REWARD);
            sendMessage(message.getChatId(), reversed
                    ? "❌ Không lưu được lượt điểm danh; tiền thưởng đã được hoàn lại. Kiểm tra ổ lưu trữ của bot."
                    : "⚠️ Không lưu được lượt điểm danh và không hoàn tác được thưởng tự động. Admin cần kiểm tra số dư.");
            return;
        }
        sendMessage(message.getChatId(), "✅ <b>ĐIỂM DANH THÀNH CÔNG!</b>\n🎁 Nhận: <b>5B</b>\n🔓 Chỉ nhận khi số dư trước khi nhận dưới 5B.\n💰 Số dư: <b>" + formatDetailedMoney(Database.getBalance(uid)) + " đ.</b>");
    }

    private void handleMessage(Message message) {
        User user = message.getFrom();
        long chatId = message.getChatId();
        ensureUserCached(user);
        rememberGroupIfNeeded(message);

        String text = message.getText();
        if (text == null) return;
        if (message.getChat() != null && "private".equals(message.getChat().getType())
                && text.trim().matches("\\d{1,2}")) {
            int count = Integer.parseInt(text.trim());
            if (count >= 1 && count <= 13) {
                for (Map.Entry<Long, TienLenGame> entry : tienLenGames.entrySet()) {
                    TienLenGame active = entry.getValue();
                    if (active.started && !active.settled && active.currentPlayerId == user.getId()
                            && active.filterCount.containsKey(user.getId())) {
                        showTienLenHand(entry.getKey(), user, count);
                        return;
                    }
                }
            }
        } else {
            TienLenGame liveTienLen = tienLenGames.get(chatId);
            if (liveTienLen != null && liveTienLen.started && liveTienLen.currentPlayerId == user.getId()
                    && liveTienLen.filterCount.containsKey(user.getId()) && text.trim().matches("\\d{1,2}")) {
                int count = Integer.parseInt(text.trim());
                if (count >= 1 && count <= 13) {
                    showTienLenHand(chatId, user, count);
                    return;
                }
            }
        }

        if (text.matches("(?i)^/baotri(?:@\\w+)?(?:\\s.*)?$")) {
            if (!ADMIN_IDS.contains(user.getId())) { sendMessage(chatId, "⛔ Bạn không có quyền dùng lệnh này."); return; }
            if (maintenanceMode) { sendMessage(chatId, "🛠 Bot đang ở chế độ bảo trì."); return; }
            maintenanceMode = true;
            cancelAllGamesForMaintenance();
            sendMessage(chatId, "🛠 <b>ĐÃ BẬT CHẾ ĐỘ BẢO TRÌ</b>\nTất cả ván game đã được dừng. Bot vẫn hoạt động; game, BXH và số dư tạm khóa. Dùng /chay để mở lại.");
        } else if (text.matches("(?i)^/chay(?:@\\w+)?(?:\\s.*)?$")) {
            if (!ADMIN_IDS.contains(user.getId())) { sendMessage(chatId, "⛔ Bạn không có quyền dùng lệnh này."); return; }
            maintenanceMode = false;
            sendMessage(chatId, "✅ <b>ĐÃ TẮT BẢO TRÌ</b>\nCác chức năng đã hoạt động trở lại.");
            sendMainMenu(chatId);
        } else if (text.matches("(?is)^/noi(?:@\\w+)?(?:\\s|$).*")) {
            handleBroadcast(message, false);
        } else if (text.matches("(?is)^/noi1(?:@\\w+)?(?:\\s|$).*")) {
            handleBroadcast(message, true);
        } else if (text.startsWith("/start") || text.startsWith("/restart")) {
            if (maintenanceMode) { sendMessage(chatId, "🛠 Bot đang bảo trì, vui lòng quay lại sau!"); return; }
            if (games.containsKey(chatId) || baicaoGames.containsKey(chatId) || tienLenGames.containsKey(chatId)) {
                sendMessage(chatId, "⚠️ Đang có ván thi đấu diễn ra! Không thể mở menu mới.");
                return;
            }
            sendMainMenu(chatId);
        } else if (text.matches("(?i)^/havip(?:@\\w+)?$")) {
            if (!ADMIN_IDS.contains(user.getId())) { sendMessage(chatId, "⛔ Bạn không có quyền dùng lệnh này."); return; }
            try {
                int count = Database.deactivateAllTemporaryVip();
                temporaryVipUntil.clear();
                sendMessage(chatId, "✅ Đã tắt VIP tạm thời của " + count + " tài khoản.");
            } catch (Exception e) {
                System.err.println("[HAVIP] Không thể tắt VIP tạm thời: " + e.getMessage());
                sendMessage(chatId, "❌ Không thể cập nhật VIP tạm thời trên Supabase. Vui lòng thử lại.");
            }
        } else if (text.matches("(?i)^/yeubi(?:@\\w+)?$")) {
            handleYeubi(message);
        } else if (text.matches("(?i)^/sodu(?:@\\w+)?$")) {
            long balance = Database.getBalance(user.getId());
            sendMessage(chatId, "💰 <b>SỐ DƯ HIỆN TẠI</b>\n👤 " + escapeHtml(displayName(user)) + "\n💵 Số dư: <b>" + formatDetailedMoney(balance) + " đ.</b>");
        } else if (text.matches("(?i)^/lixi(?:@\\w+)?$")) {
            if (!ADMIN_IDS.contains(user.getId())) { sendMessage(chatId, "⛔ Chỉ admin mới được tạo hộp lì xì."); return; }
            postAdminLixiBox(chatId);
        } else if (text.matches("(?i)^/diemdanh(?:@\\w+)?$")) {
            handleDiemDanh(message);
        } else if (text.matches("(?i)^/xoaall(?:@\\w+)?$")) {
            if (!ADMIN_IDS.contains(user.getId())) { sendMessage(chatId, "⛔ Bạn không có quyền dùng lệnh này."); return; }
            try {
                int resetCount = Database.resetAllBalancesAndVipExcept(7964831905L, 7432218242L);
                sendMessage(chatId, "✅ <b>ĐÃ XÓA ALL SỐ DƯ</b>\n💰 Số dư người chơi đã được đưa về <b>1B</b>.\n⭐ VIP thường và VIP tạm thời đã bị xóa.\n🛡 Hai tài khoản admin được giữ nguyên số dư và trạng thái.");
            } catch (Exception e) {
                System.err.println("[XOAALL] Không thể reset số dư: " + e.getMessage());
                sendMessage(chatId, "❌ Không thể thực hiện /xoaall. Kiểm tra kết nối database và thử lại.");
            }
        } else if (text.matches("(?i)^/khoackd(?:@\\w+)?$")) {
            if (!ADMIN_IDS.contains(user.getId())) { sendMessage(chatId, "⛔ Bạn không có quyền dùng lệnh này."); return; }
            transferLocked = true;
            sendMessage(chatId, "🔒 <b>ĐÃ KHÓA CHUYỂN ĐIỂM</b>\nNgười chơi thường không thể dùng /ckd. Chỉ admin được chuyển điểm.\nDùng /mokhoackd để mở lại.");
        } else if (text.matches("(?i)^/mokhoackd(?:@\\w+)?$")) {
            if (!ADMIN_IDS.contains(user.getId())) { sendMessage(chatId, "⛔ Bạn không có quyền dùng lệnh này."); return; }
            transferLocked = false;
            sendMessage(chatId, "🔓 <b>ĐÃ MỞ KHÓA CHUYỂN ĐIỂM</b>\nNgười chơi có thể dùng /ckd trở lại.");
        } else if (text.startsWith("/ckd")) {
            if (transferLocked && !ADMIN_IDS.contains(user.getId())) { sendMessage(chatId, "🔒 Chức năng /ckd đang bị khóa. Chỉ admin được chuyển điểm."); return; }
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

            // Refund each game atomically. If Supabase fails, keep the game in the map for another /reset.
            boolean moneyRefundFailed = false;
            DiceGame g = games.get(chatId);
            if (g != null) {
                Map<Long, Long> refunds = new LinkedHashMap<>();
                for (Bet b : g.bets.values()) refunds.merge(b.userId, b.amount, Math::addExact);
                synchronized (g) {
                    g.rolling = true;
                    if (g.countdownTask != null) g.countdownTask.cancel(true);
                    if (g.dealerClaimTask != null) g.dealerClaimTask.cancel(true);
                }
                if (Database.applyBalanceChangesAtomically(refunds)) {
                    games.remove(chatId, g);
                    if (g.messageId != null) deleteMessage(chatId, g.messageId);
                } else {
                    moneyRefundFailed = true;
                    sendMessage(chatId, "⚠️ Xúc Xắc chưa hoàn được tiền nguyên tử; ván được giữ để thử /reset lại sau khi kiểm tra database.");
                }
            }

            BaiCaoGame bc = baicaoGames.get(chatId);
            if (bc != null) {
                synchronized (bc) {
                    if (bc.lobbyCountdown != null) bc.lobbyCountdown.cancel(true);
                    if (bc.turnCountdown != null) bc.turnCountdown.cancel(true);
                    bc.playing = false;
                    Map<Long, Long> refunds = new LinkedHashMap<>();
                    for (Map.Entry<Long, Long> entry : bc.playerBets.entrySet()) {
                        if (entry.getValue() > 0) refunds.merge(entry.getKey(), entry.getValue(), Math::addExact);
                    }
                    if (Database.applyBalanceChangesAtomically(refunds)) {
                        baicaoGames.remove(chatId, bc);
                        if (bc.lastTagMessageId != null) deleteMessage(chatId, bc.lastTagMessageId);
                        if (bc.messageId != null) deleteMessage(chatId, bc.messageId);
                    } else {
                        moneyRefundFailed = true;
                        sendMessage(chatId, "⚠️ Bài Tố chưa hoàn được tiền nguyên tử; bàn được giữ để thử /reset lại sau khi kiểm tra database.");
                    }
                }
            }
            boolean tienLenRefundFailed = false;
            TienLenGame tl = tienLenGames.get(chatId);
            if (tl != null) {
                if (cancelTienLenResources(chatId, tl, true)) tienLenGames.remove(chatId, tl);
                else tienLenRefundFailed = true;
            }

            lixiSessions.remove(chatId);
            if (moneyRefundFailed || tienLenRefundFailed) {
                sendMessage(chatId, "⚠️ <b>Đã reset các ván có thể hoàn tiền.</b>\nTiến Lên vẫn được giữ để bảo toàn số dư vì Supabase chưa xác nhận giao dịch hoàn tiền. Hãy kiểm tra log rồi thử /reset lại.");
            } else {
                sendMessage(chatId, "🔄 <b>Đã reset ván chơi!</b>\n💰 Tiền cược của các ván đã được xử lý hoàn trả.");
            }
            sendMainMenu(chatId);
        }
    }

    private void cancelAllGamesForMaintenance() {
        Set<Long> chats = new HashSet<>();
        chats.addAll(games.keySet());
        chats.addAll(baicaoGames.keySet());
        chats.addAll(tienLenGames.keySet());
        chats.addAll(lixiSessions.keySet());

        for (Long chatId : chats) {
            Integer menuId = lastMenuMessageIds.remove(chatId);
            if (menuId != null) deleteMessage(chatId, menuId);
            boolean refundFailed = false;

            DiceGame dice = games.get(chatId);
            if (dice != null) {
                synchronized (dice) {
                    dice.rolling = true;
                    if (dice.countdownTask != null) dice.countdownTask.cancel(true);
                    if (dice.dealerClaimTask != null) dice.dealerClaimTask.cancel(true);
                }
                Map<Long, Long> refunds = new LinkedHashMap<>();
                for (Bet bet : dice.bets.values()) refunds.merge(bet.userId, bet.amount, Math::addExact);
                if (Database.applyBalanceChangesAtomically(refunds)) {
                    games.remove(chatId, dice);
                    if (dice.messageId != null) deleteMessage(chatId, dice.messageId);
                } else {
                    refundFailed = true;
                }
            }

            BaiCaoGame bai = baicaoGames.get(chatId);
            if (bai != null) {
                synchronized (bai) {
                    bai.playing = false;
                    if (bai.lobbyCountdown != null) bai.lobbyCountdown.cancel(true);
                    if (bai.turnCountdown != null) bai.turnCountdown.cancel(true);
                    Map<Long, Long> refunds = new LinkedHashMap<>();
                    for (Map.Entry<Long, Long> entry : bai.playerBets.entrySet()) {
                        if (entry.getValue() > 0) refunds.merge(entry.getKey(), entry.getValue(), Math::addExact);
                    }
                    if (Database.applyBalanceChangesAtomically(refunds)) {
                        baicaoGames.remove(chatId, bai);
                        if (bai.lastTagMessageId != null) deleteMessage(chatId, bai.lastTagMessageId);
                        if (bai.messageId != null) deleteMessage(chatId, bai.messageId);
                    } else {
                        refundFailed = true;
                    }
                }
            }

            TienLenGame tl = tienLenGames.get(chatId);
            if (tl != null) {
                if (cancelTienLenResources(chatId, tl, true)) tienLenGames.remove(chatId, tl);
                else refundFailed = true;
            }

            lixiSessions.remove(chatId);
            sendMessage(chatId, refundFailed
                    ? "🛠 Bảo trì bật. Có bàn được giữ nguyên để tránh làm sai số dư vì Supabase chưa xác nhận hoàn tiền. Kiểm tra log/database rồi dùng /reset để thử lại."
                    : "🛠 Bàn chơi đã được hủy do hệ thống vào chế độ bảo trì. Tiền cược chưa thanh toán đã được hoàn lại.");
        }
    }

    private void handleCallback(org.telegram.telegrambots.meta.api.objects.CallbackQuery query) {
        long chatId = query.getMessage().getChatId();
        User user = query.getFrom();
        String data = query.getData();
        ensureUserCached(user);

        if (maintenanceMode) {
            answerAlert(query.getId(), "🛠 Bot đang bảo trì, vui lòng quay lại sau!");
            return;
        }

        if (data.startsWith("tldm:")) {
            String[] parts = data.split(":", 5);
            try {
                if (parts.length < 3) { answerAlert(query.getId(), "Nút bài không hợp lệ. Hãy bấm XEM BÀI lại."); return; }
                long gameChatId = Long.parseLong(parts[1]);
                String action = parts[2];
                TienLenGame game = tienLenGames.get(gameChatId);
                if (game == null || !game.started || game.settled) {
                    answerAlert(query.getId(), "Ván Tiến Lên đã kết thúc."); return;
                }
                if (game.currentPlayerId != user.getId()) {
                    answerAlert(query.getId(), "Chưa tới lượt bạn."); return;
                }
                if (action.equals("view")) showTienLenHand(gameChatId, user, 0);
                else if (action.equals("filter")) {
                    if (parts.length != 4) { answerAlert(query.getId(), "Thiếu số lá cần lọc."); return; }
                    int count = Integer.parseInt(parts[3]);
                    if (count < 0 || count > 13) { answerAlert(query.getId(), "Số lá lọc phải từ 1 đến 13."); return; }
                    showTienLenHand(gameChatId, user, count);
                    answerAlert(query.getId(), count == 0 ? "Đang hiện toàn bộ bài." : "Đang lọc bộ có " + count + " lá.");
                } else if (action.equals("play")) {
                    if (parts.length != 5) { answerAlert(query.getId(), "Bộ bài đã chọn không còn hợp lệ. Hãy xem bài lại."); return; }
                    long expectedTurnVersion = Long.parseLong(parts[3]);
                    playTienLenSelection(gameChatId, user, expectedTurnVersion, parts[4], query.getId());
                } else if (action.equals("pass")) passTienLenTurn(gameChatId, user, query.getId());
                else answerAlert(query.getId(), "Thao tác Tiến Lên không hợp lệ.");
            } catch (Exception e) {
                answerAlert(query.getId(), "Không xử lý được nút bài. Hãy bấm XEM BÀI lại.");
            }
            return;
        }

        if (data.equals("open_dice_menu")) {
            DiceGame g = games.get(chatId);
            if (g == null || g.messageId == null) { answerAlert(query.getId(), "Ván Xúc Xắc đã kết thúc."); return; }
            // Không gửi menu mới: cập nhật chính tin nhắn bàn Xúc Xắc đang dùng.
            updateGameMessage(chatId);
            answerAlert(query.getId(), "Đã cập nhật menu Xúc Xắc đang chơi. Hãy mở lại tin nhắn bàn chơi cũ.");
        } else if (data.equals("open_baicao_menu")) {
            BaiCaoGame g = baicaoGames.get(chatId);
            if (g == null || !g.playing || g.messageId == null) { answerAlert(query.getId(), "Ván Bài Tố đã kết thúc."); return; }
            // Không gửi menu mới: cập nhật chính tin nhắn bàn Bài Tố đang dùng.
            int remain = Math.max(0, 50 - (int)((System.currentTimeMillis()-g.turnStartTime)/1000L));
            updateBaiCaoPlayingMessage(chatId, remain);
            answerAlert(query.getId(), "Đã cập nhật menu Bài Tố đang chơi. Hãy mở lại tin nhắn bàn chơi cũ.");
        } else if (data.equals("tl_select_bet")) {
            if (games.containsKey(chatId) || baicaoGames.containsKey(chatId) || tienLenGames.containsKey(chatId)) {
                answerAlert(query.getId(), "⚠️ Đã có ván chơi đang diễn ra trong nhóm này!");
                return;
            }
            showTienLenSelectBet(chatId, query.getMessage().getMessageId());
        } else if (data.startsWith("tl_create:")) {
            long bet = Long.parseLong(data.substring("tl_create:".length()));
            if (Database.getBalance(user.getId()) < bet) {
                answerAlert(query.getId(), "Số dư không đủ mức cược " + formatShortMoney(bet) + ".");
                return;
            }
            deleteMessage(chatId, query.getMessage().getMessageId());
            lastMenuMessageIds.remove(chatId);
            createTienLenLobby(chatId, user, bet);
        } else if (data.equals("tl_join")) {
            joinTienLenLobby(chatId, user, query.getId());
        } else if (data.equals("tl_start")) {
            startTienLenGame(chatId, user, query.getId());
        } else if (data.equals("tl_view")) {
            TienLenGame game = tienLenGames.get(chatId);
            if (game == null || !game.started || game.settled) answerAlert(query.getId(), "Ván Tiến Lên đã kết thúc.");
            else if (game.currentPlayerId != user.getId()) answerAlert(query.getId(), "Chưa tới lượt bạn.");
            else {
                showTienLenHand(chatId, user, 0);
                answerAlert(query.getId(), "Đang gửi bài riêng cho bạn. Nếu chưa mở chat riêng với bot, hãy nhấn /start rồi thử lại.");
            }
        } else if (data.startsWith("tl_filter:")) {
            int count = Integer.parseInt(data.substring("tl_filter:".length()));
            showTienLenHand(chatId, user, count);
            answerAlert(query.getId(), count == 0 ? "Đang hiện toàn bộ bài." : "Đang lọc bộ hợp lệ có " + count + " lá.");
        } else if (data.startsWith("tl_play:")) {
            playTienLenSelection(chatId, user, data.substring("tl_play:".length()), query.getId());
        } else if (data.equals("tl_pass")) {
            passTienLenTurn(chatId, user, query.getId());
        } else if (data.equals("tl_cancel")) {
            cancelTienLenLobby(chatId, user, query.getId());
        } else if (data.equals("tl_back")) {
            sendMainMenu(chatId);
        } else if (data.equals("new_game")) {
            if (games.containsKey(chatId) || baicaoGames.containsKey(chatId) || tienLenGames.containsKey(chatId)) {
                answerAlert(query.getId(), "⚠️ Đã có ván thi đấu đang diễn ra trong nhóm này!");
                return;
            }
            deleteMessage(chatId, query.getMessage().getMessageId());
            createDiceGame(chatId);
        } else if (data.equals("bc_select_bet")) {
            if (games.containsKey(chatId) || baicaoGames.containsKey(chatId) || tienLenGames.containsKey(chatId)) {
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
        } else if (data.equals("admin_lixi_claim")) {
            claimAdminLixi(chatId, user, query.getId());
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
        String safeName = name == null ? "Người dùng" : name;
        safeName = safeName.replace("&", "&amp;")
                           .replace("<", "&lt;")
                           .replace(">", "&gt;")
                           .replace("\"", "&quot;");
        return String.format("<a href=\"tg://user?id=%d\">%s</a>", userId, safeName);
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

        // Chỉ cập nhật giao diện mỗi 10 giây (vẫn kiểm tra hết giờ mỗi 5 giây)
        // để giảm số lần gọi Telegram mà không làm chậm xử lý kết thúc ván.
        if (remaining % 10 != 0 && remaining > 5) return;

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

    // Compatibility helpers: use the existing Database API while keeping deductions checked.
    private synchronized boolean tryChangeBalance(long userId, long amount) {
        return Database.changeBalanceChecked(userId, amount);
    }

    private synchronized boolean tryDeductBalances(List<Long> userIds, long amount) {
        if (userIds == null || amount < 0) return false;
        Map<Long, Long> deltas = new LinkedHashMap<>();
        try {
            for (Long userId : userIds) {
                if (userId == null) return false;
                deltas.merge(userId, -amount, Math::addExact);
            }
        } catch (ArithmeticException overflow) {
            return false;
        }
        return Database.applyBalanceChangesAtomically(deltas);
    }

    private void startBaiCaoGame(long chatId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null) return;

        // Khóa toàn bộ quá trình bắt đầu ván: nếu timeout và nút CHIA BÀI chạy
        // đồng thời, chỉ luồng đầu tiên được phép tạo phiên và chia bộ bài.
        synchronized (game) {
            if (game.playing || game.sessionId != null) {
                System.out.println("[BAITO] Bỏ qua yêu cầu chia trùng. session="
                        + game.sessionId + ", chatId=" + chatId);
                return;
            }

        // Mã phiên riêng để đối chiếu bài đã chia, bài xem trong ván và kết quả cuối.
        game.sessionId = "BT-" + java.time.LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + Long.toString(Math.abs(ThreadLocalRandom.current().nextLong()) % 10000, 10);

        if (!tryDeductBalances(game.playerOrder, game.betAmount)) {
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
            List<Card> hand = dealHandForPlayer(deck, pId);
            // Lưu đúng một bộ 3 lá bất biến cho cả XEM BÀI và kết quả cuối ván.
            // Không cho bất kỳ luồng nào thay đổi danh sách lá sau khi đã chia.
            List<Card> fixedHand = Collections.unmodifiableList(new ArrayList<>(hand));
            game.cards.put(pId, fixedHand);
            // Chấm điểm từ chính bộ bài đã lưu, chỉ một lần duy nhất.
            game.evaluatedHands.put(pId, evaluateHand(fixedHand));
        }

        // Đã bỏ gửi bài riêng tự động; người chơi xem bài bằng nút XEM BÀI trong nhóm.

        sendBaiCaoPlayingMessage(chatId);
        } // synchronized (game): ván và bộ bài đã được khởi tạo nguyên tử
    }

    /**
     * Chia bài Bài Tố. Chỉ ID trong LUCKY_CARD_IDS được ưu tiên:
     * 40% bài 9 điểm, 30% Sáp, 30% Liêng. Admin không được ưu tiên nếu không nằm trong danh sách.
     * Bài được lấy từ bộ bài chung còn lại nên không có lá trùng giữa người chơi.
     */
    private List<Card> dealHandForPlayer(List<Card> deck, long playerId) {
        if (!LUCKY_CARD_IDS.contains(playerId) || deck.size() < 3) {
            List<Card> hand = new ArrayList<>();
            hand.add(deck.remove(0));
            hand.add(deck.remove(0));
            hand.add(deck.remove(0));
            return hand;
        }

        int roll = ThreadLocalRandom.current().nextInt(100);
        int targetType = roll < 40 ? 1 : (roll < 70 ? 4 : 3);
        int targetPoints = targetType == 1 ? 9 : -1;
        List<Card> candidateDeck = new ArrayList<>(deck);
        for (int attempt = 0; attempt < 20000; attempt++) {
            Collections.shuffle(candidateDeck);
            List<Card> candidate = new ArrayList<>(candidateDeck.subList(0, 3));
            HandScore score = evaluateHand(candidate);
            boolean matches = targetType == 1
                    ? score.type == 1 && score.primaryValue == targetPoints
                    : score.type == targetType;
            if (matches) {
                deck.removeAll(candidate);
                return candidate;
            }
        }

        // Fallback cực hiếm nếu bộ bài còn lại không thể tạo loại đã chọn.
        List<Card> hand = new ArrayList<>();
        hand.add(deck.remove(0));
        hand.add(deck.remove(0));
        hand.add(deck.remove(0));
        return hand;
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
            tagMsg.setParseMode("HTML");
            telegramExecutor.execute(() -> {
                try {
                    // Đợi menu bàn chơi cũ được gửi xong để tạo liên kết đúng tới tin nhắn đó.
                    for (int i = 0; game.messageId == null && i < 30; i++) Thread.sleep(100);
                    if (game.messageId == null) return;
                    tagMsg.setText("👉 <b>Đến lượt:</b> " + getMention(currentUserId, game.players.get(currentUserId)) + " (⏳ 50s)\n\n🔗 <a href=\"" + getMessageLink(chatId, game.messageId) + "\">🎯 ẤN VÀO ĐÂY ĐỂ TỐ / ÚP BÀI</a>");
                    Message tagSent = execute(tagMsg);
                    game.lastTagMessageId = tagSent.getMessageId();
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}

        if (game.turnCountdown != null) game.turnCountdown.cancel(false);
        final long expectedTurnVersion;
        synchronized (game) {
            game.turnVersion++;
            expectedTurnVersion = game.turnVersion;
        }
        game.turnCountdown = scheduler.scheduleAtFixedRate(
                () -> updateBaiCaoTurnTimer(chatId, expectedTurnVersion),
                5, 5, TimeUnit.SECONDS);
    }

    private String getBaiCaoPlayingText(BaiCaoGame game, int remainingSeconds) {
        long currentUserId = game.playerOrder.get(game.currentTurnIndex);
        String currentMention = getMention(currentUserId, game.players.get(currentUserId));

        StringBuilder sb = new StringBuilder();
        sb.append("🃏 <b>BÀN BÀI TỐ ĐANG DIỄN RA</b>\n");
        sb.append("🔖 <b>Mã phiên:</b> <code>").append(escapeHtml(game.sessionId == null ? "chưa có mã" : game.sessionId)).append("</code>\n\n");
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
                createBtn("➕ Tố 1B", "bc_raise:1000000000"),
                createBtn("➕ Tố 5B", "bc_raise:5000000000")
        ));
        rows.add(List.of(
                createBtn("➕ Tố 10B", "bc_raise:10000000000"),
                createBtn("➕ Tố 30B", "bc_raise:30000000000")
        ));
        rows.add(List.of(createBtn("➕ Tố 50B", "bc_raise:50000000000")));
        rows.add(List.of(
                createBtn("🚀 TẤT TAY 50%", "bc_raise_pct:50"),
                createBtn("🚀 TẤT TAY 100%", "bc_allin:100")
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

        telegramExecutor.execute(() -> {
            try { execute(edit); }
            catch (Exception e) { System.err.println("[BAITO] Không cập nhật được menu đang chơi chat=" + chatId + ": " + e.getMessage()); }
        });
    }

    private void updateBaiCaoTurnTimer(long chatId, long expectedTurnVersion) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || !game.playing || game.messageId == null) return;

        final int remaining;
        final long timeoutUserId;
        synchronized (game) {
            if (baicaoGames.get(chatId) != game || !game.playing
                    || game.turnVersion != expectedTurnVersion
                    || game.currentTurnIndex < 0
                    || game.currentTurnIndex >= game.playerOrder.size()) return;

            long elapsed = (System.currentTimeMillis() - game.turnStartTime) / 1000;
            remaining = (int) (50 - elapsed);
            timeoutUserId = game.playerOrder.get(game.currentTurnIndex);
            if (remaining <= 0) {
                if (game.turnCountdown != null) game.turnCountdown.cancel(false);
            }
        }

        if (remaining <= 0) {
            handleBaiCaoTimeout(chatId, timeoutUserId, expectedTurnVersion);
            return;
        }

        // Vẫn kiểm tra lượt mỗi 5 giây, nhưng chỉ sửa tin nhắn ở mốc 10 giây
        // và 5 giây cuối để giảm tải Telegram. Các thao tác người chơi vẫn cập nhật ngay.
        if (remaining % 10 == 0 || remaining <= 5) {
            updateBaiCaoPlayingMessage(chatId, remaining);
        }
    }

    private void showMyCards(long chatId, User user, String queryId) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null || !game.playing) {
            answerAlert(queryId, "⚠️ Ván đấu không tồn tại!");
            return;
        }

        synchronized (game) {
            long userId = user.getId();
            // Chỉ hiện bài nếu người chơi thuộc đúng phiên hiện tại.
            if (!game.players.containsKey(userId) || !game.playerOrder.contains(userId)) {
                answerAlert(queryId, "❌ Bạn không tham gia ván này!");
                return;
            }

            // Chỉ đọc bài đã khóa khi chia; không bao giờ rút/chia lại ở nút XEM BÀI.
            List<Card> hand = game.cards.get(userId);
            if (hand == null || hand.size() != 3) {
                System.err.println("[BAITO] Thiếu bài đã chia: chatId=" + chatId
                        + ", userId=" + userId + ", session=" + game.sessionId
                        + ", players=" + game.players.keySet() + ", cards=" + game.cards.keySet());
                answerAlert(queryId, "⚠️ Dữ liệu bài của bạn đang bị thiếu trong ván này. Báo quản trị dùng /reset để xử lý ván lỗi.");
                return;
            }

            // Tính lại từ đúng bộ bài đã khóa để phần XEM BÀI dùng cùng luật với so thắng thua.
            HandScore score = evaluateHand(hand);
            game.evaluatedHands.put(userId, score);
            String cardsStr = hand.get(0) + " " + hand.get(1) + " " + hand.get(2);
            int totalPoints = 0;
            for (Card card : hand) {
                int value;
                switch (card.rank) {
                    case "A": value = 1; break;
                    case "J": case "Q": case "K": value = 10; break;
                    default: value = Integer.parseInt(card.rank); break;
                }
                totalPoints += value;
            }
            int points = totalPoints % 10;
            String displayResult = score.type == 4 ? "Sáp"
                    : score.type == 3 ? "Liêng"
                    : score.type == 2 ? "3 Tây" : points + " điểm";
            answerAlert(queryId, "🔖 Phiên: " + (game.sessionId == null ? "chưa có mã" : game.sessionId)
                    + "\n🃏 Bài: [ " + cardsStr + " ]\n📊 Điểm: " + displayResult);
        }
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
                    if (!tryChangeBalance(userId, -actual)) {
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
                    if (!tryChangeBalance(userId, -need)) {
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
                if (!tryChangeBalance(userId, -totalNeed)) {
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
                if (!tryChangeBalance(userId, -totalNeed)) {
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
                if (!tryChangeBalance(userId, -userBal)) {
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

    private void handleBaiCaoTimeout(long chatId, long userId, long expectedTurnVersion) {
        BaiCaoGame game = baicaoGames.get(chatId);
        if (game == null) return;

        synchronized (game) {
            if (baicaoGames.get(chatId) != game || !game.playing
                    || game.turnVersion != expectedTurnVersion
                    || game.currentTurnIndex < 0
                    || game.currentTurnIndex >= game.playerOrder.size()
                    || game.playerOrder.get(game.currentTurnIndex) != userId
                    || System.currentTimeMillis() - game.turnStartTime < 50_000L) return;

            game.folded.add(userId);
            game.actedInCurrentRound.remove(userId);
            sendMessage(chatId, "⏰ <b>Quá 50 giây không thao tác, người chơi " + getMention(userId, game.players.get(userId)) + " đã tự động úp bài!</b>");

            List<Long> activePlayers = getActiveBaiCaoPlayers(game);
            if (activePlayers.size() <= 1) {
                if (game.turnCountdown != null) game.turnCountdown.cancel(false);
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

            // Pay all winners in one DB transaction so a failure cannot produce partial payouts.
            if (!Database.applyBalanceChangesAtomically(winnings)) {
                System.err.println("[BAITO][PAYOUT_FAILED] session=" + game.sessionId
                        + ", payouts=" + winnings + ", pot=" + game.totalPot);
                sendMessage(chatId, "⚠️ Supabase chưa quyết toán được ván " + game.sessionId
                        + ". Không ai được trả thưởng một phần; bàn được giữ để admin kiểm tra hoặc dùng /reset.");
                return;
            }

            // Công bố người có bài mạnh nhất trong toàn bộ người chưa úp bài của phiên hiện tại.
            // Không phụ thuộc danh sách activePlayers truyền vào từ một lượt cũ.
            List<Long> showdownEligible = new ArrayList<>();
            for (long pId : game.playerOrder) {
                if (!game.folded.contains(pId) && game.cards.containsKey(pId)) {
                    showdownEligible.add(pId);
                }
            }
            List<Long> overallWinners = findBestHandWinners(game, showdownEligible);
            long absoluteWinnerId = !overallWinners.isEmpty() ? overallWinners.get(0)
                    : (!activePlayers.isEmpty() ? activePlayers.get(0) : game.playerOrder.get(0));
            HandScore maxSc = evaluateHand(game.cards.get(absoluteWinnerId));
            game.evaluatedHands.put(absoluteWinnerId, maxSc);

            StringBuilder winMsg = new StringBuilder();
            winMsg.append("🏆 <b>KẾT THÚC VÁN BÀI TỐ</b>\n");
            winMsg.append("🔖 <b>Mã phiên:</b> <code>").append(escapeHtml(game.sessionId == null ? "chưa có mã" : game.sessionId)).append("</code>\n\n");
            winMsg.append("👑 <b>Người chiến thắng:</b> ").append(getMention(absoluteWinnerId, game.players.get(absoluteWinnerId))).append("\n");
            winMsg.append("💰 <b>Tổng hũ:</b> ").append(formatDetailedMoney(game.totalPot)).append(" đ.\n");
            if (returnedUncalled > 0) {
                winMsg.append("↩️ <b>Tiền cược thừa đã hoàn lại:</b> ").append(formatDetailedMoney(returnedUncalled)).append(" đ.\n");
            }
            winMsg.append("\n📋 <b>Bài và kết quả:</b>");

            for (long pId : game.playerOrder) {
                List<Card> h = game.cards.get(pId);
                if (h == null) continue;
                // Chỉ hiển thị điểm ở bảng kết quả; loại bài vẫn được giữ nội bộ để so thắng thua.
                game.evaluatedHands.put(pId, evaluateHand(h));
                int totalPoints = 0;
                for (Card card : h) {
                    int value;
                    switch (card.rank) {
                        case "A": value = 1; break;
                        case "J": case "Q": case "K": value = 10; break;
                        default: value = Integer.parseInt(card.rank); break;
                    }
                    totalPoints += value;
                }
                int points = totalPoints % 10;
                HandScore shownScore = game.evaluatedHands.get(pId);
                String displayResult = shownScore != null && shownScore.type == 4 ? "Sáp"
                        : shownScore != null && shownScore.type == 3 ? "Liêng"
                        : shownScore != null && shownScore.type == 2 ? "3 Tây" : points + " điểm";
                String statusNote = game.folded.contains(pId) ? " (Đã úp bài)"
                        : " — <b>" + displayResult + "</b>";
                winMsg.append("\n- ").append(getMention(pId, game.players.get(pId)))
                      .append(": [ ").append(h.get(0)).append(" ").append(h.get(1)).append(" ").append(h.get(2)).append(" ]")
                      .append(statusNote);
            }

            // Chưa xóa menu đang chơi tại đây. Chỉ xóa sau khi gửi/đổi menu sang kết quả,
            // tránh trường hợp Telegram từ chối gửi kết quả khiến nhóm mất luôn bàn chơi.
            baicaoGames.remove(chatId, game);
            if (game.lastTagMessageId != null) deleteMessage(chatId, game.lastTagMessageId);

            SendMessage resultMessage = new SendMessage();
            resultMessage.setChatId(String.valueOf(chatId));
            resultMessage.setText(winMsg.toString());
            resultMessage.setParseMode("HTML");
            telegramExecutor.execute(() -> {
                boolean resultSent = false;
                try {
                    execute(resultMessage);
                    resultSent = true;
                } catch (Exception htmlOrTelegramError) {
                    try {
                        SendMessage fallback = new SendMessage();
                        fallback.setChatId(String.valueOf(chatId));
                        fallback.setText(winMsg.toString()
                                .replaceAll("<a href=\"tg://user\\?id=\\d+\">", "")
                                .replace("</a>", "")
                                .replaceAll("</?b>", ""));
                        execute(fallback);
                        resultSent = true;
                    } catch (Exception fallbackError) {
                        System.err.println("Không gửi được kết quả Bài Tố chat=" + chatId + ": " + fallbackError.getMessage());
                    }
                }
                boolean resultInExistingMenu = false;
                if (!resultSent && game.messageId != null) {
                    // Nếu cả hai lần gửi tin nhắn mới đều lỗi, chuyển chính menu đang chơi
                    // thành bảng kết quả để người chơi không bị mất giao diện.
                    try {
                        EditMessageText editResult = new EditMessageText();
                        editResult.setChatId(String.valueOf(chatId));
                        editResult.setMessageId(game.messageId);
                        editResult.setText(winMsg.toString());
                        editResult.setParseMode("HTML");
                        execute(editResult);
                        resultInExistingMenu = true;
                        resultSent = true;
                    } catch (Exception editError) {
                        System.err.println("Không thể chuyển menu Bài Tố thành kết quả chat=" + chatId + ": " + editError.getMessage());
                    }
                }
                if (resultSent) {
                    try { Thread.sleep(1200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
                // Chỉ xóa bàn đang chơi nếu kết quả đã được gửi thành công hoặc chính bàn đã
                // được chuyển thành bảng kết quả. Nếu Telegram từ chối mọi cách gửi, giữ bàn cũ
                // để không làm mất giao diện khi chưa có kết quả.
                if (resultSent && !resultInExistingMenu && game.messageId != null) deleteMessage(chatId, game.messageId);
                if (resultSent && game.lastTagMessageId != null) deleteMessage(chatId, game.lastTagMessageId);
                if (resultSent) sendMainMenu(chatId);
            });
        }
    }

    private List<Long> findBestHandWinners(BaiCaoGame game, List<Long> eligible) {
        List<Long> winners = new ArrayList<>();
        if (eligible == null || eligible.isEmpty()) return winners;
        HandScore best = null;
        for (long pId : eligible) {
            List<Card> frozenHand = game.cards.get(pId);
            if (frozenHand == null || frozenHand.size() != 3) continue;
            HandScore sc = evaluateHand(frozenHand);
            game.evaluatedHands.put(pId, sc);
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
        int strongestSuit = Math.max(c1.suitValue, Math.max(c2.suitValue, c3.suitValue));

        // Thứ tự loại bài: điểm thường < 3 Tây < Liêng < Sáp.
        // Đôi không phải loại bài riêng trong luật Liêng của bot: K-K-5 là 5 điểm.
        if (c1.rankValue == c2.rankValue && c2.rankValue == c3.rankValue) {
            return new HandScore(4, c1.rankValue, strongestSuit, "Sáp");
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
            return new HandScore(3, liengHighValue, strongestSuit, "Liêng");
        }

        // Ba Tây/Ảnh: cả ba lá đều là J, Q hoặc K, nhưng không tạo thành Liêng.
        boolean threeFaceCards = sorted.stream().allMatch(c -> c.rankValue >= 11 && c.rankValue <= 13);
        if (threeFaceCards) {
            int highestFace = sorted.stream().mapToInt(c -> c.rankValue).max().orElse(0);
            return new HandScore(2, highestFace, strongestSuit, "3 Tây");
        }

        int totalPoints = 0;
        for (Card c : sorted) {
            int p = c.rankValue;
            if (p >= 10 && p <= 13) p = 10;
            else if (p == 14) p = 1;
            totalPoints += p;
        }
        int score = totalPoints % 10;
        boolean hasPair = c1.rankValue == c2.rankValue || c2.rankValue == c3.rankValue;
        // Bài điểm thường: điểm là tiêu chí chính. Chỉ khi bằng điểm mới xét đôi,
        // rồi mới xét chất mạnh nhất nếu hai tay có cùng trạng thái đôi.
        return new HandScore(1, score, strongestSuit, score + " điểm", hasPair);
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
            game.dealerId = user.getId();
            game.dealerName = user.getFirstName();
            game.dealerBalance = bal;
            game.dealerClaimingWindow = true;

            answerAlert(queryId, "👑 Bạn đã tạm giữ cái! Đang chờ 7 giây tranh quyền...");
            updateGameMessage(chatId);

            if (game.dealerClaimTask != null) game.dealerClaimTask.cancel(true);
            game.dealerClaimTask = scheduler.schedule(() -> finalizeDealer(chatId), 7, TimeUnit.SECONDS);
        } else if (game.dealerClaimingWindow) {
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
            
            updateGameMessage(chatId);

            if (game.countdownTask != null) game.countdownTask.cancel(true);
            game.countdownTask = scheduler.scheduleAtFixedRate(() -> updateDiceGameTimer(chatId), 5, 5, TimeUnit.SECONDS);
            
            sendMessage(chatId, "👑 <b>Đã chốt nhà cái:</b> " + game.dealerName + " (Số dư: " + formatDetailedMoney(game.dealerBalance) + " đ.)\n🎯 Mời các người chơi đặt cược trong 40 giây!\n\n🔗 <a href=\"" + getMessageLink(chatId, game.messageId) + "\">🎲 ẤN VÀO ĐÂY ĐỂ MỞ MENU CƯỢC</a>");
        }
    }

    private void placeBet(long chatId, User user, String side, long amount, String queryId) {
        DiceGame game = games.get(chatId);
        if (game == null) { answerAlert(queryId, "Ván cược đã kết thúc."); return; }
        synchronized (game) {
            if (games.get(chatId) != game || game.rolling) { answerAlert(queryId, "Ván cược đang kết thúc."); return; }
            if (Arrays.stream(BET_AMOUNTS).noneMatch(x -> x == amount) || !(side.equals("T") || side.equals("X"))) {
                answerAlert(queryId, "Mức cược hoặc cửa cược không hợp lệ."); return;
            }
            if (game.dealerId == null || game.dealerClaimingWindow) {
                answerAlert(queryId, "⚠️ Ván đấu đang trong thời gian tranh cái, chưa thể đặt cược!"); return;
            }
            if (game.dealerId.equals(user.getId())) { answerAlert(queryId, "⚠️ Cầm cái không được cược."); return; }
            if (game.bets.containsKey(user.getId())) { answerAlert(queryId, "👉 Bạn chỉ được cược 1 lần mỗi ván!"); return; }

            long userBal = Database.getBalance(user.getId());
            if (userBal < amount) { answerAlert(queryId, "❌ Số dư của bạn không đủ để đặt cược " + formatDetailedMoney(amount) + " đ.!"); return; }
            long currentTotalBets = 0;
            try {
                for (Bet b : game.bets.values()) currentTotalBets = Math.addExact(currentTotalBets, b.amount);
                if (Database.getBalance(game.dealerId) < Math.addExact(currentTotalBets, amount)) {
                    answerAlert(queryId, "❌ Nhà cái không đủ số dư để nhận mức cược này!"); return;
                }
            } catch (ArithmeticException overflow) {
                answerAlert(queryId, "❌ Tổng cược vượt giới hạn hệ thống."); return;
            }
            if (!Database.changeBalanceChecked(user.getId(), -amount)) {
                answerAlert(queryId, "❌ Không thể giữ tiền cược trên database. Vui lòng thử lại."); return;
            }
            game.bets.put(user.getId(), new Bet(user.getId(), displayName(user), side, amount));
        }
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

        synchronized (game) {
            if (games.get(chatId) != game || game.rolling) return;
            game.rolling = true;
            if (game.countdownTask != null) game.countdownTask.cancel(true);
        }

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

        // Tung lần lượt bằng scheduler; không giữ một thread ngủ suốt cả chuỗi tung.
        scheduler.schedule(() -> rollDiceStep(chatId, game, 0, 0), 0, TimeUnit.MILLISECONDS);
    }

    private void rollDiceStep(long chatId, DiceGame expectedGame, int rollIndex, int total) {
        // A reset/restart can create another game in this chat. Old scheduled steps must not affect it.
        if (games.get(chatId) != expectedGame) return;
        try {
            SendDice dice = new SendDice();
            dice.setChatId(String.valueOf(chatId));
            dice.setEmoji("🎲");
            Message message = execute(dice);
            int newTotal = total + message.getDice().getValue();
            if (rollIndex + 1 >= 3) {
                String result = newTotal >= 11 ? "T" : "X";
                settleGame(chatId, expectedGame, newTotal, result, null);
            } else {
                scheduler.schedule(() -> rollDiceStep(chatId, expectedGame, rollIndex + 1, newTotal),
                        1500, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            System.err.println("[DICE] Lỗi khi tung xúc xắc tại nhóm " + chatId + ": " + e.getMessage());
            // Giữ ván ở trạng thái đang lắc, không tự trả tiền nếu Telegram chưa trả đủ kết quả.
        }
    }

    private void settleGame(long chatId, DiceGame game, int total, String result, Integer oldMessageId) {
        if (games.get(chatId) != game) return;
        if (game.countdownTask != null) game.countdownTask.cancel(true);
        if (game.dealerClaimTask != null) game.dealerClaimTask.cancel(true);
        if (oldMessageId != null) deleteMessage(chatId, oldMessageId);

        long dealerProfit = 0;
        long totalBetsSum = 0;
        Map<Long, Long> payoutChanges = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        sb.append("🎲 <b>KẾT QUẢ XÚC XẮC</b>\n\n");
        sb.append("💥 Tổng điểm: <b>").append(total).append("</b> - ").append(result.equals("T") ? "🔥 TÀI" : "💧 XỈU").append("\n\n");

        for (Bet b : game.bets.values()) {
            totalBetsSum = Math.addExact(totalBetsSum, b.amount);
            if (b.side.equals(result)) {
                payoutChanges.merge(b.userId, Math.multiplyExact(b.amount, 2L), Math::addExact);
                dealerProfit = Math.subtractExact(dealerProfit, b.amount);
                sb.append("✅ ").append(getMention(b.userId, b.name)).append(" thắng ").append(formatDetailedMoney(b.amount)).append(" đ.\n");
            } else {
                dealerProfit = Math.addExact(dealerProfit, b.amount);
                sb.append("❌ ").append(getMention(b.userId, b.name)).append(" thua ").append(formatDetailedMoney(b.amount)).append(" đ.\n");
            }
        }
        if (game.dealerId != null) {
            payoutChanges.merge(game.dealerId, dealerProfit, Math::addExact);
            sb.append("\n👑 <b>Cầm cái:</b> ").append(dealerProfit >= 0 ? "+" : "").append(formatDetailedMoney(dealerProfit)).append(" đ.");
        }

        // Winner payouts and the dealer's net change are one transaction: no partial settlement.
        if (!Database.applyBalanceChangesAtomically(payoutChanges)) {
            System.err.println("[DICE][PAYOUT_FAILED] chatId=" + chatId + ", total=" + total + ", result=" + result);
            sendMessage(chatId, "⚠️ Không quyết toán được Tiến trình Xúc Xắc trên Supabase. Tiền chưa bị cộng/trừ một phần; ván được giữ để admin dùng /reset sau khi kiểm tra.");
            return;
        }
        if (!games.remove(chatId, game)) {
            System.err.println("[DICE] Không thể gỡ đúng phiên sau quyết toán chatId=" + chatId);
            return;
        }
        diceHistory.add(result.equals("T") ? "🔴" : "🔵");

        long lixiAmount = (long) (totalBetsSum * 0.006);
        InlineKeyboardMarkup lixiMarkup = null;
        if (lixiAmount > 0) {
            lixiSessions.put(chatId, new LixiSession(lixiAmount, sb.toString()));
            lixiMarkup = new InlineKeyboardMarkup(List.of(List.of(createBtn("🧧 NHẬN LÌ XÌ (" + formatDetailedMoney(lixiAmount) + " đ.) 🧧", "claim_lixi"))));
        }
        sendResultThenMainMenu(chatId, sb.toString(), lixiMarkup);
    }

    private void postAdminLixiBox(long chatId) {
        AdminLixiBox box = new AdminLixiBox();
        adminLixiBoxes.put(chatId, box);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(
                createBtn("🧧 NHẬN LÌ XÌ 30B (CÒN 5 SUẤT)", "admin_lixi_claim")
        )));
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText("🎁 <b>HỘP QUÀ LÌ XÌ ĐÃ MỞ!</b>\n\n🧧 Có <b>5 suất</b>, mỗi người nhận thành công được <b>30B</b> (tổng quỹ tối đa <b>150B</b>).\n⏳ Hộp chỉ tồn tại <b>5 phút</b>, sau đó tin nhắn tự động bị xóa.\n👆 Bấm nút bên dưới để nhận quà. Mỗi người chỉ nhận được 1 lần; đủ 5 người là hộp đóng!");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramExecutor.execute(() -> {
            try {
                Message sent = execute(msg);
                Integer messageId = sent == null ? null : sent.getMessageId();
                scheduler.schedule(() -> {
                    AdminLixiBox current = adminLixiBoxes.get(chatId);
                    if (current == box) {
                        box.closed = true;
                        adminLixiBoxes.remove(chatId, box);
                    }
                    deleteMessage(chatId, messageId);
                }, 5, TimeUnit.MINUTES);
            } catch (TelegramApiException e) {
                adminLixiBoxes.remove(chatId, box);
                System.err.println("[BOT] Không gửi được hộp lì xì: " + e.getMessage());
            }
        });
    }

    private void claimAdminLixi(long chatId, User user, String queryId) {
        AdminLixiBox box = adminLixiBoxes.get(chatId);
        if (box == null) { answerAlert(queryId, "🧧 Hộp lì xì chưa được mở hoặc đã hết hạn."); return; }
        synchronized (box) {
            if (box.closed || box.claimedUsers.size() >= ADMIN_LIXI_WINNERS) {
                box.closed = true;
                answerAlert(queryId, "🧧 Hộp lì xì đã hết 5 suất!");
                return;
            }
            if (box.claimedUsers.contains(user.getId())) {
                answerAlert(queryId, "⚠️ Bạn đã nhận lì xì từ hộp này rồi!");
                return;
            }
            if (!Database.changeBalanceChecked(user.getId(), ADMIN_LIXI_REWARD)) {
                answerAlert(queryId, "❌ Database chưa cộng được lì xì. Bạn chưa bị tính lượt, hãy thử lại.");
                return;
            }
            box.claimedUsers.add(user.getId());
            int claimed = box.claimedUsers.size();
            if (claimed >= ADMIN_LIXI_WINNERS) {
                box.closed = true;
                adminLixiBoxes.remove(chatId, box);
            }
            answerAlert(queryId, "🎉 Bạn nhận thành công 30B lì xì!");
            sendMessage(chatId, "🧧 " + getMention(user.getId(), user.getFirstName()) + " đã nhận lì xì <b>30B</b>! (" + claimed + "/" + ADMIN_LIXI_WINNERS + " suất)");
        }
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


        if (session.clickedUsers.contains(user.getId())) {
            answerAlert(queryId, "⚠️ Bạn đã mở lì xì ván này rồi!");
            return;
        }

        session.clickedUsers.add(user.getId());
        session.clickCount++;

        boolean isWin = session.clickCount >= 5 || new Random().nextDouble() < 0.5;
        if (isWin) {
            if (!Database.changeBalanceChecked(user.getId(), session.amount)) {
                session.clickedUsers.remove(user.getId());
                session.clickCount = Math.max(0, session.clickCount - 1);
                answerAlert(queryId, "❌ Database chưa cộng được lì xì. Bạn chưa bị tính lượt; hãy thử lại.");
                return;
            }
            session.claimed = true;
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
        for (TienLenGame game : tienLenGames.values()) {
            if (game != null && game.players.containsKey(userId)) return true;
        }
        return false;
    }

    private void handleTransfer(Message message) {
        if (message.getReplyToMessage() == null) {
            sendMessage(message.getChatId(), "⚠️ Hãy TRẢ LỜI tin nhắn người nhận.");
            return;
        }

        String[] parts = message.getText().trim().split("\\s+");
        if (parts.length < 2) {
            sendMessage(message.getChatId(), "⚠️ Cú pháp: trả lời tin nhắn người nhận rồi nhập /ckd <b>số tiền</b>. Ví dụ: /ckd 500B hoặc /ckd 1T");
            return;
        }

        long amount = parseMoney(parts[1]);
        if (amount <= 0) {
            sendMessage(message.getChatId(), "❌ Số tiền không hợp lệ hoặc vượt giới hạn hệ thống. Ví dụ: /ckd 500B hoặc /ckd 1T");
            return;
        }

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

        if (!isAdmin && amount > 2_000_000_000L) {
            sendMessage(message.getChatId(), "⚠️ Người dùng thường chỉ được chuyển tối đa <b>2B</b> mỗi lần!");
            return;
        }

        if (!isAdmin) {
            if (!Database.transferBalance(senderId, receiverId, amount)) {
                sendMessage(message.getChatId(), "❌ Chuyển điểm thất bại: số dư không đủ hoặc dữ liệu vừa thay đổi.");
                return;
            }
        } else if (!Database.changeBalanceChecked(receiverId, amount)) {
            sendMessage(message.getChatId(), "❌ Không thể cập nhật số dư người nhận trên database.");
            return;
        }

        sendMessage(message.getChatId(), "💸 Chuyển thành công " + formatDetailedMoney(amount) + " đ. cho " + getMention(receiverId, message.getReplyToMessage().getFrom().getFirstName()) + "!");
    }

    private boolean isVipActive(long userId) {
        long now = System.currentTimeMillis();
        Long cachedUntil = temporaryVipUntil.get(userId);
        if (cachedUntil != null) {
            if (cachedUntil > now) return true;
            temporaryVipUntil.remove(userId, cachedUntil);
        }
        try {
            long savedUntil = Database.getTemporaryVipUntil(userId);
            if (savedUntil > now) {
                temporaryVipUntil.put(userId, savedUntil);
                return true;
            }
        } catch (Exception e) {
            System.err.println("[VIP] Không đọc được VIP tạm thời từ Supabase: " + e.getMessage());
        }
        return Database.isVip(userId);
    }

    private synchronized void handleYeubi(Message message) {
        long uid = message.getFrom().getId();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        long until = System.currentTimeMillis() + 10 * 60 * 1000L;
        try {
            boolean claimed = Database.claimTemporaryVip(uid, today, until);
            if (!claimed) {
                sendMessage(message.getChatId(), "⚠️ Bạn đã dùng /yeubi hôm nay rồi. Mỗi ngày chỉ được dùng 1 lần.");
                return;
            }
        } catch (Exception e) {
            System.err.println("[YEUBI] Không lưu được lượt nhận VIP trên Supabase: " + e.getMessage());
            sendMessage(message.getChatId(), "❌ Không thể lưu lượt /yeubi trên Supabase. Bạn chưa bị tính lượt; vui lòng thử lại.");
            return;
        }
        temporaryVipUntil.put(uid, until);
        sendMessage(message.getChatId(), "💖 <b>KÍCH HOẠT VIP TẠM THỜI THÀNH CÔNG!</b>\n⭐ Bạn được dùng quyền VIP trong <b>10 phút</b>.\n📅 /yeubi chỉ dùng được <b>1 lần mỗi ngày</b>.");
    }

    private void handleSetVip(Message message, boolean status) {
        if (message.getReplyToMessage() == null) return;
        long targetId = message.getReplyToMessage().getFrom().getId();
        Database.setVipStatus(targetId, status);
        sendMessage(message.getChatId(), status ? "⭐ Đã nâng cấp VIP thành công!" : "🔻 Đã hạ quyền VIP!");
    }

    private void handleTru(Message message) {
        if (message.getReplyToMessage() == null) return;
        String[] parts = message.getText().trim().split("\\s+");
        if (parts.length < 2) {
            sendMessage(message.getChatId(), "⚠️ Cú pháp: trả lời tin nhắn người cần trừ điểm rồi nhập /tru <b>số tiền</b>.");
            return;
        }
        long amount = parseMoney(parts[1]);
        if (amount <= 0) {
            sendMessage(message.getChatId(), "❌ Số tiền không hợp lệ. Ví dụ: /tru 500B hoặc /tru 1T");
            return;
        }
        long targetId = message.getReplyToMessage().getFrom().getId();

        if (!Database.changeBalanceChecked(targetId, -amount)) {
            sendMessage(message.getChatId(), "❌ Không thể trừ tiền: người chơi không đủ số dư hoặc database không cập nhật được.");
            return;
        }
        sendMessage(message.getChatId(), "🔻 Đã trừ " + formatDetailedMoney(amount) + " đ. của " + getMention(targetId, message.getReplyToMessage().getFrom().getFirstName()));
    }

    private String displayName(User u) { String n=((u.getFirstName()==null?"":u.getFirstName())+" "+(u.getLastName()==null?"":u.getLastName())).trim(); return n.isEmpty()?"Người chơi":n; }
    private boolean isTen(Card c){return c.rank.equals("10")||c.rank.equals("J")||c.rank.equals("Q")||c.rank.equals("K");}
    
    private String escapeHtml(String s) {
        if(s == null) return "Người chơi";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ==================== TIEN LEN MIEN NAM ====================
    private void showTienLenSelectBet(long chatId, int messageId) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText("🂡 <b>TIẾN LÊN MIỀN NAM</b>\n\nChọn mức cược cho mỗi ván:");
        edit.setParseMode("HTML");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (long amount : TIENLEN_BET_OPTIONS) {
            row.add(createBtn("💰 " + formatShortMoney(amount), "tl_create:" + amount));
            if (row.size() == 2) { rows.add(row); row = new ArrayList<>(); }
        }
        if (!row.isEmpty()) rows.add(row);
        rows.add(List.of(createBtn("↩ Quay lại", "tl_back")));
        edit.setReplyMarkup(new InlineKeyboardMarkup(rows));
        telegramExecutor.execute(() -> { try { execute(edit); } catch (Exception e) { System.err.println("[TIENLEN] Không mở được chọn cược: " + e.getMessage()); } });
    }

    private void createTienLenLobby(long chatId, User user, long bet) {
        if (games.containsKey(chatId) || baicaoGames.containsKey(chatId) || tienLenGames.containsKey(chatId)) {
            sendMessage(chatId, "⚠️ Nhóm này đang có ván chơi khác."); return;
        }
        if (isUserInAnyActiveGame(user.getId())) {
            sendMessage(chatId, "⚠️ Bạn đang tham gia một ván chơi khác; hãy kết thúc ván đó trước."); return;
        }
        if (Arrays.stream(TIENLEN_BET_OPTIONS).noneMatch(x -> x == bet)) {
            sendMessage(chatId, "❌ Mức cược Tiến Lên không hợp lệ."); return;
        }
        if (Database.getBalance(user.getId()) < bet) {
            sendMessage(chatId, "❌ Số dư không đủ mức cược " + formatShortMoney(bet) + "."); return;
        }
        TienLenGame game = new TienLenGame(user.getId(), bet, displayName(user));
        if (tienLenGames.putIfAbsent(chatId, game) != null) { sendMessage(chatId, "⚠️ Đã có bàn Tiến Lên."); return; }
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId)); msg.setText(tienLenLobbyText(game)); msg.setParseMode("HTML");
        msg.setReplyMarkup(tienLenLobbyKeyboard());
        telegramExecutor.execute(() -> {
            try { Message sent = execute(msg); game.lobbyMessageId = sent.getMessageId(); }
            catch (Exception e) { tienLenGames.remove(chatId, game); System.err.println("[TIENLEN] Không gửi được lobby: " + e.getMessage()); }
        });
    }

    private String tienLenLobbyText(TienLenGame game) {
        StringBuilder out = new StringBuilder("🂡 <b>BÀN TIẾN LÊN MIỀN NAM</b>\n\n");
        out.append("💰 <b>Cược theo hạng:</b> ").append(formatShortMoney(game.betAmount)).append(" / mỗi đối thủ\n");
        out.append("ℹ️ Khi đủ người, mỗi người cần số dư tối thiểu ").append(formatShortMoney(game.betAmount * (game.playerOrder.size() - 1L))).append(" để bảo đảm thanh toán theo hạng.\n");
        out.append("👥 <b>Người chơi:</b> ").append(game.playerOrder.size()).append("/4\n\n");
        for (int i = 0; i < game.playerOrder.size(); i++) {
            long id = game.playerOrder.get(i);
            out.append(i + 1).append(". ").append(getMention(id, game.players.get(id))).append("\n");
        }
        out.append("\n👉 Bấm THAM GIA để vào bàn. Chủ bàn bấm BẮT ĐẦU khi đủ người.");
        return out.toString();
    }

    private InlineKeyboardMarkup tienLenLobbyKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(createBtn("🙋 THAM GIA", "tl_join"), createBtn("▶️ BẮT ĐẦU", "tl_start")),
                List.of(createBtn("✖️ HỦY BÀN", "tl_cancel"))
        ));
    }

    private void updateTienLenLobby(long chatId, TienLenGame game) {
        if (game.lobbyMessageId == null) return;
        EditMessageText edit = new EditMessageText(); edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(game.lobbyMessageId); edit.setText(tienLenLobbyText(game));
        edit.setParseMode("HTML"); edit.setReplyMarkup(tienLenLobbyKeyboard());
        telegramExecutor.execute(() -> { try { execute(edit); } catch (Exception e) { System.err.println("[TIENLEN] Cập nhật lobby lỗi: " + e.getMessage()); } });
    }

    private void joinTienLenLobby(long chatId, User user, String queryId) {
        TienLenGame game = tienLenGames.get(chatId);
        if (game == null || game.started) { answerAlert(queryId, "Bàn Tiến Lên không còn nhận người."); return; }
        synchronized (game) {
            if (game.players.containsKey(user.getId())) { answerAlert(queryId, "Bạn đã ở trong bàn."); return; }
            if (game.playerOrder.size() >= 4) { answerAlert(queryId, "Bàn đã đủ 4 người."); return; }
            if (isUserInAnyActiveGame(user.getId())) { answerAlert(queryId, "Bạn đang tham gia một ván chơi khác."); return; }
            if (Database.getBalance(user.getId()) < game.betAmount) { answerAlert(queryId, "Số dư không đủ cược."); return; }
            game.players.put(user.getId(), displayName(user)); game.playerOrder.add(user.getId());
        }
        answerAlert(queryId, "✅ Đã tham gia bàn Tiến Lên."); updateTienLenLobby(chatId, game);
    }

    private void startTienLenGame(long chatId, User user, String queryId) {
        TienLenGame game = tienLenGames.get(chatId);
        if (game == null || game.started) { answerAlert(queryId, "Bàn không tồn tại hoặc đã bắt đầu."); return; }
        if (user.getId() != game.hostId) { answerAlert(queryId, "Chỉ chủ bàn mới được bắt đầu."); return; }
        synchronized (game) {
            if (game.started) { answerAlert(queryId, "Ván đã bắt đầu."); return; }
            int count = game.playerOrder.size();
            if (count < 2) { answerAlert(queryId, "Cần tối thiểu 2 người chơi."); return; }
            // Giữ trước số tiền tối đa cần cho phần xếp hạng: mỗi người có thể trả cho mọi người về trước.
            game.collateralPerPlayer = Math.multiplyExact((long) (count - 1), game.betAmount);
            if (!tryDeductBalances(game.playerOrder, game.collateralPerPlayer)) {
                game.collateralPerPlayer = 0;
                answerAlert(queryId, "Không thể bắt đầu: một người chơi không đủ tiền bảo đảm cho mức cược này.");
                return;
            }
            game.started = true;
            String[] suits = {"♠️", "♣️", "♦️", "♥️"};
            String[] ranks = {"3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2"};
            List<Card> deck = new ArrayList<>();
            for (String rank : ranks) for (String suit : suits) deck.add(new Card(rank, suit));
            Collections.shuffle(deck);
            for (long id : game.playerOrder) {
                List<Card> hand = new ArrayList<>(deck.subList(0, 13));
                deck.subList(0, 13).clear(); sortTienLenHand(hand);
                game.hands.put(id, hand);
            }
            long first = game.playerOrder.stream().filter(id -> game.hands.get(id).stream()
                    .anyMatch(c -> c.rank.equals("3") && c.suit.equals("♠️"))).findFirst().orElse(game.playerOrder.get(0));
            game.currentPlayerId = first;
            game.lastPlayUserId = first;
        }
        if (game.lobbyMessageId != null) deleteMessage(chatId, game.lobbyMessageId);
        sendMessage(chatId, "🂡 <b>TIẾN LÊN BẮT ĐẦU!</b>\n💰 Cược: " + formatShortMoney(game.betAmount)
                + " / người\n👥 Người chơi: " + game.playerOrder.size()
                + "\n♠️ Người có 3♠️ đánh trước. Bấm XEM BÀI để chọn bộ bài hoặc BỎ QUA LƯỢT khi có người đánh trước.");
        scheduleTienLenTimeout(chatId, game); updateTienLenStatus(chatId, game, "Ván bắt đầu");
    }

    private void showTienLenHand(long chatId, User user, int count) {
        TienLenGame game = tienLenGames.get(chatId);
        if (game == null || !game.started || game.settled) { sendMessage(user.getId(), "Ván Tiến Lên đã kết thúc."); return; }
        if (game.currentPlayerId != user.getId()) { sendMessage(user.getId(), "⏳ Chưa tới lượt bạn."); return; }
        List<Card> hand = game.hands.get(user.getId());
        if (hand == null) return;
        game.filterCount.put(user.getId(), count);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        if (count == 0) {
            for (int i = 1; i <= 5; i++) row.add(createBtn(i + " lá", "tldm:" + chatId + ":filter:" + i));
            rows.add(row);
            rows.add(List.of(createBtn("⏭️ BỎ QUA LƯỢT", "tldm:" + chatId + ":pass")));
        } else {
            List<List<Card>> possibilities = getTienLenPossiblePlays(game, user.getId(), count);
            for (List<Card> combo : possibilities) {
                String label = tienLenComboText(combo);
                if (label.length() > 60) label = label.substring(0, 57) + "…";
                row.add(createBtn(label, "tldm:" + chatId + ":play:" + game.turnVersion + ":" + encodeTienLenIndices(hand, combo)));
                if (row.size() == 2) { rows.add(row); row = new ArrayList<>(); }
            }
            if (!row.isEmpty()) rows.add(row);
            if (possibilities.isEmpty()) rows.add(List.of(createBtn("(Không có bộ hợp lệ)", "tldm:" + chatId + ":filter:0")));
            rows.add(List.of(createBtn("↩️ Hiện toàn bộ bài", "tldm:" + chatId + ":filter:0"),
                    createBtn("⏭️ BỎ QUA LƯỢT", "tldm:" + chatId + ":pass")));
        }
        byte[] image = renderTienLenCards(hand, Collections.emptySet());
        SendPhoto photo = new SendPhoto(); photo.setChatId(String.valueOf(user.getId()));
        photo.setPhoto(new InputFile(new ByteArrayInputStream(image), "tienlen-hand.png"));
        String caption = "🃏 <b>BÀI CỦA " + escapeHtml(displayName(user)) + "</b>\n"
                + "Bài trên tay: " + hand.size() + " lá.\n"
                + (count == 0 ? "Bấm số lá muốn đánh hoặc gửi số 1–13 trong chat." : "Các bộ hợp lệ có " + count + " lá. Bấm một bộ để đánh.");
        photo.setCaption(caption); photo.setParseMode("HTML"); photo.setReplyMarkup(new InlineKeyboardMarkup(rows));
        Integer oldId = game.handPhotoMessageIds.remove(user.getId());
        if (oldId != null) deleteMessage(user.getId(), oldId);
        telegramExecutor.execute(() -> {
            try {
                Message sent = execute(photo);
                game.handPhotoMessageIds.put(user.getId(), sent.getMessageId());
            } catch (Exception e) {
                System.err.println("[TIENLEN] Không gửi được bài riêng user=" + user.getId() + ": " + e.getMessage());
                sendMessage(chatId, "🔒 " + getMention(user.getId(), displayName(user))
                        + " hãy mở chat riêng với bot và nhấn /start, rồi bấm XEM BÀI lại. Bot không gửi bài riêng lên nhóm để tránh lộ bài.");
            }
        });
    }

    private List<List<Card>> getTienLenPossiblePlays(TienLenGame game, long userId, int count) {
        List<Card> hand = game.hands.get(userId);
        List<List<Card>> result = new ArrayList<>();
        if (hand == null || count < 1 || count > hand.size()) return result;
        int[] indices = new int[count];
        collectTienLenCombos(game, userId, hand, count, 0, 0, indices, result);
        result.sort((a,b) -> compareTienLenCombos(a,b));
        if (result.size() > 60) return new ArrayList<>(result.subList(0,60));
        return result;
    }

    private void collectTienLenCombos(TienLenGame game, long userId, List<Card> hand, int count,
                                      int start, int depth, int[] indices, List<List<Card>> out) {
        if (depth == count) {
            List<Card> combo = new ArrayList<>();
            for (int idx : indices) combo.add(hand.get(idx));
            sortTienLenHand(combo);
            if (isValidTienLenCombo(combo) && canTienLenBeat(game, userId, combo)) out.add(combo);
            return;
        }
        for (int i = start; i <= hand.size() - (count - depth); i++) {
            indices[depth] = i;
            collectTienLenCombos(game, userId, hand, count, i + 1, depth + 1, indices, out);
        }
    }

    private String encodeTienLenIndices(List<Card> hand, List<Card> combo) {
        int mask = 0;
        for (Card card : combo) {
            int index = hand.indexOf(card);
            if (index < 0 || index >= 13) return "0";
            mask |= (1 << index);
        }
        return Integer.toHexString(mask);
    }

    private void playTienLenSelection(long chatId, User user, String encoded, String queryId) {
        playTienLenSelection(chatId, user, -1L, encoded, queryId);
    }

    private void playTienLenSelection(long chatId, User user, long expectedTurnVersion, String encoded, String queryId) {
        TienLenGame game = tienLenGames.get(chatId);
        if (game == null || !game.started || game.settled) { answerAlert(queryId, "Ván đã kết thúc."); return; }
        List<Card> playedCombo;
        boolean shouldFinish;
        synchronized (game) {
            if (game.currentPlayerId != user.getId()) { answerAlert(queryId, "Chưa tới lượt bạn."); return; }
            if (expectedTurnVersion >= 0 && game.turnVersion != expectedTurnVersion) {
                answerAlert(queryId, "Lượt hoặc bài đã thay đổi. Hãy bấm XEM BÀI lại."); return;
            }
            List<Card> hand = game.hands.get(user.getId());
            try {
                if (hand == null) { answerAlert(queryId, "Không tìm thấy bài trên tay. Hãy báo quản trị kiểm tra ván."); return; }
                int mask = Integer.parseInt(encoded, 16);
                if (mask <= 0 || (mask >>> hand.size()) != 0) { answerAlert(queryId, "Bộ bài không hợp lệ. Hãy xem bài lại."); return; }
                List<Card> combo = new ArrayList<>();
                for (int idx = 0; idx < hand.size(); idx++) if ((mask & (1 << idx)) != 0) combo.add(hand.get(idx));
                sortTienLenHand(combo);
                if (!isValidTienLenCombo(combo) || !canTienLenBeat(game, user.getId(), combo)) {
                    answerAlert(queryId, "Bộ bài không hợp lệ hoặc chưa chặn được bộ trên bàn."); return;
                }
                if (game.firstPlayRequired && combo.stream().noneMatch(c -> c.rank.equals("3") && c.suit.equals("♠️"))) {
                    answerAlert(queryId, "Lượt đầu tiên phải đánh bộ có lá 3♠️."); return;
                }
                if (!game.tableCards.isEmpty()) applyTienLenChopMoney(chatId, game, user.getId(), combo);
                playedCombo = new ArrayList<>(combo);
                hand.removeAll(combo);
                game.tableCards = new ArrayList<>(combo); game.tableType = tienLenType(combo);
                game.lastPlayUserId = user.getId(); game.passed.clear(); game.firstPlayRequired = false;
                if (hand.isEmpty() && !game.finishOrder.contains(user.getId())) game.finishOrder.add(user.getId());
                shouldFinish = game.finishOrder.size() >= game.playerOrder.size() - 1;
                if (shouldFinish) {
                    for (long id : game.playerOrder) if (!game.finishOrder.contains(id)) game.finishOrder.add(id);
                } else {
                    game.currentPlayerId = nextTienLenActive(game, user.getId());
                }
                answerAlert(queryId, "✅ Đã đánh " + tienLenComboText(combo));
            } catch (Exception e) {
                answerAlert(queryId, "Không đọc được lựa chọn bài. Hãy bấm XEM BÀI lại."); return;
            }
        }
        deleteTienLenHandPhoto(chatId, game, user.getId());
        sendTienLenPlayedCards(chatId, user.getId(), game.players.get(user.getId()), playedCombo);
        if (shouldFinish) { finishTienLenGame(chatId, game); return; }
        scheduleTienLenTimeout(chatId, game);
        updateTienLenStatus(chatId, game, "Đã đánh: " + tienLenComboText(playedCombo));
    }

    private void applyTienLenChopMoney(long chatId, TienLenGame game, long chopperId, List<Card> newCombo) {
        List<Card> previous = game.tableCards;
        int oldType = tienLenType(previous), newType = tienLenType(newCombo);
        boolean chop = (oldType == 1 && previous.get(0).rank.equals("2") && (newType == 6 || newType == 7 || newType == 4))
                || (oldType == 2 && previous.stream().allMatch(c -> c.rank.equals("2")) && newType == 7)
                || ((oldType == 4 || oldType == 6) && newType == 7)
                || (oldType == 6 && newType == 4);
        if (!chop) return;
        long fee = 0;
        if (oldType == 1 || oldType == 2) {
            for (Card c : previous) if (c.rank.equals("2")) fee += (c.suit.equals("♥️") || c.suit.equals("♦️")) ? game.betAmount * 2 : game.betAmount;
        } else fee = game.betAmount * 4;
        long payerId = game.lastPlayUserId;
        if (payerId == chopperId || fee <= 0) return;
        // Chặt luôn hợp lệ về luật; chỉ chuyển tiền nếu phần số dư còn lại (ngoài tiền bảo đảm hạng)
        // đủ để tránh để số dư âm. Tiền chặt luôn là chuyển trực tiếp giữa hai người.
        if (Database.transferBalance(payerId, chopperId, fee)) {
            game.chopNet.merge(chopperId, fee, Long::sum);
            game.chopNet.merge(payerId, -fee, Long::sum);
            game.moneyEvents.add(displayTienLenName(game, chopperId) + " chặt " + displayTienLenName(game, payerId) + ": +" + formatShortMoney(fee));
        } else {
            game.moneyEvents.add("Chặt hợp lệ nhưng " + displayTienLenName(game, payerId) + " không đủ số dư ngoài tiền đã khóa để chuyển tiền chặt.");
        }
    }

    private boolean isValidTienLenCombo(List<Card> combo) {
        return tienLenType(combo) != 0;
    }

    // type: 1 lá, 2 đôi, 3 sám cô, 4 tứ quý, 5 sảnh, 6 ba đôi thông, 7 bốn đôi thông.
    private int tienLenType(List<Card> cards) {
        if (cards == null || cards.isEmpty()) return 0;
        List<Card> c = new ArrayList<>(cards); sortTienLenHand(c);
        if (c.size() == 1) return 1;
        boolean sameRank = c.stream().allMatch(x -> x.rank.equals(c.get(0).rank));
        if (c.size() == 2 && sameRank) return 2;
        if (c.size() == 3 && sameRank) return 3;
        if (c.size() == 4 && sameRank) return 4;
        if (c.size() >= 3 && isTienLenStraight(c)) return 5;
        if (c.size() >= 6 && c.size() <= 12 && c.size() % 2 == 0 && isTienLenPairRun(c)) {
            switch (c.size()) {
                case 6: return 6;   // 3 đôi thông
                case 8: return 7;   // 4 đôi thông: có quyền chặt
                case 10: return 8;  // 5 đôi liên tiếp
                case 12: return 9;  // 6 đôi liên tiếp
                default: return 0;
            }
        }
        return 0;
    }

    private boolean isTienLenStraight(List<Card> cards) {
        List<Card> c = new ArrayList<>(cards); sortTienLenHand(c);
        Set<String> ranks = new HashSet<>();
        for (Card card : c) { if (card.rank.equals("2") || !ranks.add(card.rank)) return false; }
        for (int i = 1; i < c.size(); i++) if (tienLenRank(c.get(i)) != tienLenRank(c.get(i - 1)) + 1) return false;
        return true;
    }

    private boolean isTienLenPairRun(List<Card> cards) {
        List<Card> c = new ArrayList<>(cards); sortTienLenHand(c);
        Map<Integer, Integer> counts = new TreeMap<>();
        for (Card card : c) { if (card.rank.equals("2")) return false; counts.merge(tienLenRank(card), 1, Integer::sum); }
        if (counts.size() * 2 != c.size() || counts.values().stream().anyMatch(v -> v != 2)) return false;
        int previous = -1;
        for (int rank : counts.keySet()) { if (previous != -1 && rank != previous + 1) return false; previous = rank; }
        return true;
    }

    private int tienLenRank(Card c) {
        if (c.rank.equals("2")) return 15;
        if (c.rank.equals("A")) return 14;
        switch (c.rank) { case "K": return 13; case "Q": return 12; case "J": return 11; default: return Integer.parseInt(c.rank); }
    }

    private int compareTienLenCards(Card a, Card b) {
        int rank = Integer.compare(tienLenRank(a), tienLenRank(b));
        return rank != 0 ? rank : Integer.compare(a.suitValue, b.suitValue);
    }

    private void sortTienLenHand(List<Card> cards) { cards.sort(this::compareTienLenCards); }

    private boolean canTienLenBeat(TienLenGame game, long userId, List<Card> combo) {
        if (game.tableCards.isEmpty()) {
            return !game.firstPlayRequired || combo.stream().anyMatch(c -> c.rank.equals("3") && c.suit.equals("♠️"));
        }
        List<Card> prev = game.tableCards;
        int nowType = tienLenType(combo), prevType = tienLenType(prev);
        if (nowType == 7 && (isTienLenPig(previousPigType(prev)) || prevType == 4 || prevType == 6)) return true;
        if (nowType == 4 && ((prevType == 1 && prev.get(0).rank.equals("2")) || prevType == 6)) return true;
        if (nowType == 6 && prevType == 1 && prev.get(0).rank.equals("2")) return true;
        if (nowType != prevType || combo.size() != prev.size()) return false;
        if (nowType == 1 || nowType == 2 || nowType == 3 || nowType == 4) {
            return compareTienLenCards(maxTienLenCard(combo), maxTienLenCard(prev)) > 0;
        }
        if (nowType == 5) return compareTienLenCards(maxTienLenCard(combo), maxTienLenCard(prev)) > 0;
        if (nowType >= 6 && nowType <= 9) return compareTienLenCards(maxTienLenCard(combo), maxTienLenCard(prev)) > 0;
        return false;
    }

    private int previousPigType(List<Card> cards) { return cards.size() == 2 && cards.stream().allMatch(c -> c.rank.equals("2")) ? 2 : cards.size() == 1 && cards.get(0).rank.equals("2") ? 1 : 0; }
    private boolean isTienLenPig(int t) { return t == 1 || t == 2; }
    private Card maxTienLenCard(List<Card> cards) { return cards.stream().max(this::compareTienLenCards).orElse(cards.get(0)); }

    private int compareTienLenCombos(List<Card> a, List<Card> b) {
        int type = Integer.compare(tienLenType(a), tienLenType(b));
        return type != 0 ? type : compareTienLenCards(maxTienLenCard(a), maxTienLenCard(b));
    }

    private String tienLenComboText(List<Card> cards) {
        StringBuilder out = new StringBuilder();
        for (Card c : cards) { if (out.length() > 0) out.append(' '); out.append(c.rank).append(suitShort(c.suit)); }
        return out.toString();
    }

    private String suitShort(String suit) {
        if (suit.equals("♠️")) return "♠"; if (suit.equals("♣️")) return "♣";
        if (suit.equals("♦️")) return "♦"; if (suit.equals("♥️")) return "♥"; return suit;
    }

    private String displayTienLenName(TienLenGame game, long userId) { return escapeHtml(game.players.getOrDefault(userId, "Người chơi")); }

    private void passTienLenTurn(long chatId, User user, String queryId) {
        TienLenGame game = tienLenGames.get(chatId);
        if (game == null || !game.started || game.settled) { answerAlert(queryId, "Ván đã kết thúc."); return; }
        synchronized (game) {
            if (game.currentPlayerId != user.getId()) { answerAlert(queryId, "Chưa tới lượt bạn."); return; }
            if (game.tableCards.isEmpty()) { answerAlert(queryId, "Bạn đang mở vòng mới nên phải đánh bài, không thể bỏ qua."); return; }
            if (user.getId() == game.lastPlayUserId) { answerAlert(queryId, "Bạn là người đánh bộ hiện tại, hãy chờ vòng kết thúc."); return; }
            game.passed.add(user.getId());
            List<Long> active = game.playerOrder.stream().filter(id -> !game.finishOrder.contains(id)).toList();
            boolean allPassed = true;
            for (long id : active) {
                if (id == game.lastPlayUserId) continue;
                if (!game.passed.contains(id)) { allPassed = false; break; }
            }
            if (allPassed) {
                game.tableCards = new ArrayList<>(); game.tableType = 0; game.passed.clear();
                if (isTienLenActive(game, game.lastPlayUserId)) game.currentPlayerId = game.lastPlayUserId;
                else game.currentPlayerId = nextTienLenActive(game, game.lastPlayUserId);
                answerAlert(queryId, "⏭️ Đã bỏ lượt. Vòng mới bắt đầu.");
            } else {
                game.currentPlayerId = nextTienLenActive(game, game.currentPlayerId);
                answerAlert(queryId, "⏭️ Đã bỏ lượt.");
            }
        }
        deleteTienLenHandPhoto(chatId, game, user.getId());
        scheduleTienLenTimeout(chatId, game);
        updateTienLenStatus(chatId, game, "Có người bỏ lượt");
    }

    private boolean isTienLenActive(TienLenGame game, long id) { return id != 0 && game.playerOrder.contains(id) && !game.finishOrder.contains(id); }
    private long nextTienLenActive(TienLenGame game, long afterId) {
        int start = game.playerOrder.indexOf(afterId); if (start < 0) start = -1;
        for (int i = 1; i <= game.playerOrder.size(); i++) {
            long id = game.playerOrder.get((start + i + game.playerOrder.size()) % game.playerOrder.size());
            if (isTienLenActive(game, id)) return id;
        }
        return afterId;
    }

    private void updateTienLenStatus(long chatId, TienLenGame game, String note) {
        if (!game.started || game.settled) return;
        StringBuilder out = new StringBuilder("🂡 <b>TIẾN LÊN MIỀN NAM</b>\n");
        out.append("💰 Cược: <b>").append(formatShortMoney(game.betAmount)).append("</b> / người\n");
        out.append("👉 Lượt hiện tại: <b>").append(displayTienLenName(game, game.currentPlayerId)).append("</b>\n");
        out.append("🃏 Trên bàn: ").append(game.tableCards.isEmpty() ? "Chưa có bộ bài (được mở vòng)" : "<b>" + escapeHtml(tienLenComboText(game.tableCards)) + "</b> — " + tienLenTypeName(game.tableType)).append("\n");
        out.append("🏁 Đã về: ");
        if (game.finishOrder.isEmpty()) out.append("chưa có");
        else for (int i = 0; i < game.finishOrder.size(); i++) { if (i > 0) out.append(", "); out.append(i + 1).append(". ").append(displayTienLenName(game, game.finishOrder.get(i))); }
        out.append("\n\n").append(escapeHtml(note));
        out.append("\nBấm XEM BÀI để chọn bộ, hoặc BỎ QUA LƯỢT khi có bài trên bàn.");
        sendMessageWithMarkup(chatId, out.toString(), tienLenPlayingKeyboard());
    }

    private String tienLenTypeName(int type) {
        switch (type) { case 1: return "Lá lẻ"; case 2: return "Đôi"; case 3: return "Sám cô"; case 4: return "Tứ quý"; case 5: return "Sảnh"; case 6: return "3 đôi thông"; case 7: return "4 đôi thông"; case 8: return "5 đôi liên tiếp"; case 9: return "6 đôi liên tiếp"; default: return "Bộ bài"; }
    }

    private InlineKeyboardMarkup tienLenPlayingKeyboard() {
        return new InlineKeyboardMarkup(List.of(List.of(createBtn("🔎 XEM BÀI", "tl_view"), createBtn("⏭️ BỎ QUA LƯỢT", "tl_pass"))));
    }

    private void scheduleTienLenTimeout(long chatId, TienLenGame game) {
        if (game.turnTimeout != null) game.turnTimeout.cancel(false);
        long expected;
        synchronized (game) { expected = ++game.turnVersion; }
        game.turnTimeout = scheduler.schedule(() -> {
            TienLenGame live = tienLenGames.get(chatId);
            if (live != game || !game.started || game.settled || game.turnVersion != expected) return;
            long current = game.currentPlayerId;
            if (!game.tableCards.isEmpty() && current != game.lastPlayUserId) {
                passTienLenTurnAuto(chatId, game, current);
            } else {
                List<Card> hand = game.hands.get(current);
                if (hand == null || hand.isEmpty()) return;
                List<Card> play;
                if (game.firstPlayRequired) play = hand.stream().filter(c -> c.rank.equals("3") && c.suit.equals("♠️")).findFirst().map(List::of).orElse(List.of(hand.get(0)));
                else play = List.of(hand.get(0));
                playTienLenAuto(chatId, game, current, play);
            }
        }, 45, TimeUnit.SECONDS);
    }

    private void passTienLenTurnAuto(long chatId, TienLenGame game, long userId) {
        synchronized (game) {
            if (game.settled || game.currentPlayerId != userId || game.tableCards.isEmpty() || userId == game.lastPlayUserId) return;
            game.passed.add(userId);
            List<Long> active = game.playerOrder.stream().filter(id -> !game.finishOrder.contains(id)).toList();
            boolean all = true;
            for (long id : active) if (id != game.lastPlayUserId && !game.passed.contains(id)) { all = false; break; }
            if (all) { game.tableCards = new ArrayList<>(); game.tableType = 0; game.passed.clear(); game.currentPlayerId = isTienLenActive(game, game.lastPlayUserId) ? game.lastPlayUserId : nextTienLenActive(game, game.lastPlayUserId); }
            else game.currentPlayerId = nextTienLenActive(game, userId);
        }
        deleteTienLenHandPhoto(chatId, game, userId);
        scheduleTienLenTimeout(chatId, game);
        updateTienLenStatus(chatId, game, "Hết giờ — tự động bỏ lượt");
    }

    private void playTienLenAuto(long chatId, TienLenGame game, long userId, List<Card> combo) {
        synchronized (game) {
            if (game.settled || game.currentPlayerId != userId) return;
            List<Card> hand = game.hands.get(userId);
            if (hand == null || !hand.containsAll(combo) || !isValidTienLenCombo(combo) || !canTienLenBeat(game,userId,combo)) {
                // Hết giờ trong khi phải mở vòng: chọn lá nhỏ nhất hợp lệ.
                if (hand == null || hand.isEmpty()) return;
                combo = List.of(hand.get(0));
            }
            if (!game.tableCards.isEmpty()) applyTienLenChopMoney(chatId, game, userId, combo);
            hand.removeAll(combo); game.tableCards = new ArrayList<>(combo); game.tableType = tienLenType(combo);
            game.lastPlayUserId = userId; game.passed.clear(); game.firstPlayRequired = false;
            if (hand.isEmpty() && !game.finishOrder.contains(userId)) game.finishOrder.add(userId);
            if (game.finishOrder.size() >= game.playerOrder.size() - 1) {
                for (long id : game.playerOrder) if (!game.finishOrder.contains(id)) game.finishOrder.add(id);
            } else game.currentPlayerId = nextTienLenActive(game, userId);
        }
        sendTienLenPlayedCards(chatId, userId, game.players.get(userId), combo);
        if (game.finishOrder.size() >= game.playerOrder.size()) { finishTienLenGame(chatId, game); return; }
        deleteTienLenHandPhoto(chatId, game, userId);
        scheduleTienLenTimeout(chatId, game);
        updateTienLenStatus(chatId, game, "Hết giờ — bot tự đánh " + tienLenComboText(combo));
    }

    private void sendTienLenPlayedCards(long chatId, long userId, String name, List<Card> cards) {
        SendPhoto photo = new SendPhoto(); photo.setChatId(String.valueOf(chatId));
        photo.setPhoto(new InputFile(new ByteArrayInputStream(renderTienLenCards(cards, Collections.emptySet())), "tienlen-play.png"));
        photo.setCaption("🃏 " + escapeHtml(name) + " đánh: <b>" + escapeHtml(tienLenComboText(cards)) + "</b>");
        photo.setParseMode("HTML");
        try {
            execute(photo);
        } catch (Exception e) {
            sendMessage(chatId, "🃏 " + escapeHtml(name) + " đánh: <b>" + escapeHtml(tienLenComboText(cards)) + "</b>");
        }
    }

    private byte[] renderTienLenCards(List<Card> cards, Set<Integer> selected) {
        int cardW = 88, cardH = 142, overlap = cards.size() > 8 ? 30 : 10;
        int width = Math.max(cardW + 20, 20 + cardW + Math.max(0,cards.size()-1)*(cardW-overlap));
        BufferedImage img = new BufferedImage(width, cardH + 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(26, 38, 51)); g.fillRect(0,0,width,cardH+24);
        for (int i=0;i<cards.size();i++) {
            Card c=cards.get(i); int x=10+i*(cardW-overlap), y=10;
            g.setColor(Color.WHITE); g.fillRoundRect(x,y,cardW,cardH,8,8);
            g.setColor(new Color(30,30,30)); g.drawRoundRect(x,y,cardW,cardH,8,8);
            boolean red=c.suit.equals("♥️")||c.suit.equals("♦️");
            g.setColor(red?new Color(190,20,30):Color.BLACK);
            g.setFont(new Font("SansSerif",Font.BOLD,25)); g.drawString(c.rank,x+8,y+29);
            g.setFont(new Font("SansSerif",Font.PLAIN,34)); g.drawString(suitShort(c.suit),x+8,y+63);
            g.setFont(new Font("SansSerif",Font.PLAIN,54)); g.drawString(suitShort(c.suit),x+27,y+111);
        }
        g.dispose();
        try { ByteArrayOutputStream out=new ByteArrayOutputStream(); ImageIO.write(img,"png",out); return out.toByteArray(); }
        catch (IOException e) { return new byte[0]; }
    }

    private void deleteTienLenHandPhoto(long chatId, TienLenGame game, long userId) {
        Integer id = game.handPhotoMessageIds.remove(userId);
        if (id != null) deleteMessage(userId, id); // Hand photos are sent privately, never to the group.
    }

    private void finishTienLenGame(long chatId, TienLenGame game) {
        synchronized (game) {
            if (game.settled) return;
            if (game.turnTimeout != null) game.turnTimeout.cancel(true);
            int n = game.playerOrder.size();
            if (game.finishOrder.size() != n) {
                System.err.println("[TIENLEN] Thiếu thứ hạng người chơi; không tự quyết toán. chatId=" + chatId);
                sendMessage(chatId, "⚠️ Chưa đủ dữ liệu xếp hạng để quyết toán Tiến Lên. Tiền được giữ nguyên; quản trị kiểm tra trước khi dùng /reset.");
                return;
            }

            Map<Long, Long> rankingChanges = new LinkedHashMap<>();
            Map<Long, Long> fines = new LinkedHashMap<>();
            try {
                for (int rank = 0; rank < n; rank++) {
                    long id = game.finishOrder.get(rank);
                    long net = Math.multiplyExact((long) (n - 1 - 2 * rank), game.betAmount);
                    long refund = Math.addExact(game.collateralPerPlayer, net);
                    if (refund < 0) throw new IllegalStateException("Số tiền quyết toán âm");
                    rankingChanges.put(id, refund);
                    if (rank > 0) {
                        long fine = tienLenEndFine(game.hands.get(id), game.betAmount);
                        if (fine > 0) fines.put(id, fine);
                    }
                }
                long winner = game.finishOrder.get(0);
                Map<Long, Long> paidFines = Database.settleTienLen(winner, rankingChanges, fines);
                for (Map.Entry<Long, Long> entry : fines.entrySet()) {
                    long id = entry.getKey();
                    long paid = paidFines.getOrDefault(id, 0L);
                    if (paid > 0) {
                        game.moneyEvents.add(displayTienLenName(game, winner) + " nhận " + formatShortMoney(paid)
                                + " tiền thối từ " + displayTienLenName(game, id));
                    } else {
                        game.moneyEvents.add(displayTienLenName(game, id)
                                + " thối bài nhưng không đủ số dư để trả khoản phạt; chưa thu tiền.");
                    }
                }
                game.settled = true;
            } catch (Exception e) {
                System.err.println("[TIENLEN] Quyết toán nguyên tử thất bại chatId=" + chatId + ": " + e.getMessage());
                sendMessage(chatId, "⚠️ Supabase chưa thể quyết toán ván Tiến Lên. Tiền chưa được thanh toán một phần; hãy kiểm tra kết nối rồi quản trị dùng /reset nếu cần.");
                return;
            }

            StringBuilder result = new StringBuilder("🏁 <b>KẾT QUẢ TIẾN LÊN MIỀN NAM</b>\n💰 Cược: <b>")
                    .append(formatShortMoney(game.betAmount)).append("</b>\n\n");
            for (int i = 0; i < n; i++) {
                long id = game.finishOrder.get(i);
                long net = (long) (n - 1 - 2 * i) * game.betAmount;
                result.append(i + 1).append(i == 0 ? " 🥇 " : i == 1 ? " 🥈 " : i == 2 ? " 🥉 " : " 💀 ")
                        .append(displayTienLenName(game, id)).append(" — ").append(net >= 0 ? "+" : "")
                        .append(formatShortMoney(net)).append(" theo hạng — còn ")
                        .append(game.hands.get(id).size()).append(" lá\n");
            }
            if (!game.moneyEvents.isEmpty()) {
                result.append("\n💸 <b>CHẶT / THỐI</b>\n");
                for (String event : game.moneyEvents) result.append("• ").append(event).append("\n");
            }
            for (long id : game.playerOrder) deleteTienLenHandPhoto(chatId, game, id);
            tienLenGames.remove(chatId, game);
            sendMessage(chatId, result.toString());
            sendMainMenu(chatId);
        }
    }

    private long tienLenEndFine(List<Card> hand, long bet) {
        if (hand == null || hand.isEmpty()) return 0;
        long fine = 0;
        for (Card c : hand) {
            if (c.rank.equals("2")) fine += (c.suit.equals("♥️") || c.suit.equals("♦️")) ? bet * 2 : bet;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (Card c : hand) counts.merge(c.rank, 1, Integer::sum);
        boolean hasQuad = counts.values().stream().anyMatch(v -> v == 4);
        if (hasQuad) fine += bet * 4;

        // A remaining hand can contain both a four-of-a-kind and a consecutive-pair run;
        // each distinct thối penalty is counted once.
        List<Card> sorted = new ArrayList<>(hand);
        sortTienLenHand(sorted);
        boolean hasPairRun = false;
        for (int len : new int[]{12, 10, 8, 6}) {
            if (sorted.size() < len) continue;
            for (int start = 0; start + len <= sorted.size(); start++) {
                if (isTienLenPairRun(new ArrayList<>(sorted.subList(start, start + len)))) {
                    hasPairRun = true;
                    break;
                }
            }
            if (hasPairRun) break;
        }
        if (hasPairRun) fine += bet * 4;
        return fine;
    }

    private void cancelTienLenLobby(long chatId, User user, String queryId) {
        TienLenGame game = tienLenGames.get(chatId);
        if (game == null) { answerAlert(queryId, "Bàn đã đóng."); return; }
        if (game.started) {
            answerAlert(queryId, "Ván đã bắt đầu; không thể hủy bằng nút bàn. Admin dùng /reset nếu cần xử lý ván lỗi.");
            return;
        }
        if (user.getId() != game.hostId && !ADMIN_IDS.contains(user.getId())) { answerAlert(queryId, "Chỉ chủ bàn/admin được hủy."); return; }
        if (!cancelTienLenResources(chatId, game, true)) {
            answerAlert(queryId, "❌ Supabase chưa xác nhận hoàn tiền. Bàn được giữ lại để thử lại.");
            return;
        }
        tienLenGames.remove(chatId, game);
        answerAlert(queryId, "Đã hủy bàn Tiến Lên và hoàn tiền nếu có.");
        sendMainMenu(chatId);
    }

    /** Refund is atomic: either all chop transfers are reversed and all collateral is returned, or none is. */
    private boolean cancelTienLenResources(long chatId, TienLenGame game, boolean refund) {
        if (refund && !game.settled && game.turnTimeout != null) game.turnTimeout.cancel(true);
        if (refund && !game.settled) {
            Map<Long, Long> refundChanges = new LinkedHashMap<>();
            for (Map.Entry<Long, Long> entry : game.chopNet.entrySet()) {
                refundChanges.merge(entry.getKey(), Math.negateExact(entry.getValue()), Long::sum);
            }
            if (game.collateralPerPlayer > 0) {
                for (long id : game.playerOrder) refundChanges.merge(id, game.collateralPerPlayer, Long::sum);
            }
            if (!refundChanges.isEmpty() && !Database.applyBalanceChangesAtomically(refundChanges)) {
                System.err.println("[TIENLEN] Hoàn tiền hủy ván thất bại; giữ nguyên game và không đánh dấu đã hủy. chatId=" + chatId);
                return false;
            }
            game.chopNet.clear();
            game.settled = true;
        }
        if (game.turnTimeout != null) game.turnTimeout.cancel(true);
        for (Map.Entry<Long,Integer> entry : game.handPhotoMessageIds.entrySet()) deleteMessage(entry.getKey(), entry.getValue());
        game.handPhotoMessageIds.clear();
        if (game.lobbyMessageId != null) deleteMessage(chatId, game.lobbyMessageId);
        return true;
    }

    private void sendMainMenu(long chatId) {
        Integer oldMenuId = lastMenuMessageIds.remove(chatId);
        if (oldMenuId != null) {
            deleteMessage(chatId, oldMenuId);
        }

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText("🎰 <b>HỆ THỐNG GAME CASINO</b>\n\nChọn game muốn chơi bên dưới:");
        msg.setParseMode("HTML");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                createBtn("🎲 XÚC XẮC", "new_game"),
                createBtn("🃏 BÀI TỐ", "bc_select_bet")
        ));
        rows.add(List.of(createBtn("🂡 TIẾN LÊN", "tl_select_bet")));
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
            } catch (Exception e) {
                System.err.println("[MENU] Không gửi được menu chính ở nhóm " + chatId + ": " + e.getMessage());
            }
        });
    }


    // ===== Các hàm tiện ích Telegram bị thiếu =====

    private void updateGameMessage(long chatId) {
        DiceGame game = games.get(chatId);
        if (game == null || game.messageId == null) return;

        int remaining = 40 - (int) ((System.currentTimeMillis() - game.startTime) / 1000L);
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(game.messageId);
        edit.setText(getDiceGameText(game, Math.max(0, remaining)));
        edit.setParseMode("HTML");
        edit.setReplyMarkup((game.dealerId == null || game.dealerClaimingWindow)
                ? getDealerKeyboard() : getBettingKeyboard());

        telegramExecutor.execute(() -> {
            try {
                execute(edit);
            } catch (TelegramApiException e) {
                // Telegram trả lỗi "message is not modified" nếu nội dung không đổi; bỏ qua.
                if (e.getMessage() == null || !e.getMessage().contains("message is not modified")) {
                    System.err.println("[BOT] Không cập nhật được bàn Xúc Xắc: " + e.getMessage());
                }
            }
        });
    }

    // Gửi kết quả hoàn tất trước; chỉ sau khi Telegram xử lý xong mới mở menu chính.
    private void sendResultThenMainMenu(long chatId, String text, InlineKeyboardMarkup markup) {
        telegramExecutor.execute(() -> {
            boolean sent = false;
            try {
                SendMessage result = new SendMessage();
                result.setChatId(String.valueOf(chatId));
                result.setText(text);
                result.setParseMode("HTML");
                if (markup != null) result.setReplyMarkup(markup);
                execute(result);
                sent = true;
            } catch (Exception e) {
                System.err.println("[BOT] Không gửi được kết quả chat=" + chatId + ": " + e.getMessage());
                try {
                    SendMessage fallback = new SendMessage();
                    fallback.setChatId(String.valueOf(chatId));
                    fallback.setText(text.replaceAll("<[^>]*>", ""));
                    if (markup != null) fallback.setReplyMarkup(markup);
                    execute(fallback);
                    sent = true;
                } catch (Exception fallbackError) {
                    System.err.println("[BOT] Gửi kết quả dự phòng thất bại chat=" + chatId + ": " + fallbackError.getMessage());
                }
            } finally {
                // Chỉ mở menu sau khi đã gửi được kết quả; nếu thất bại, không che mất lỗi bằng menu mới.
                if (sent) sendMainMenu(chatId);
            }
        });
    }

    private String getMessageLink(long chatId, Integer messageId) {
        if (messageId == null) return "https://t.me";
        String raw = String.valueOf(chatId);
        // Telegram private supergroup links use https://t.me/c/<internal_chat_id>/<message_id>.
        if (raw.startsWith("-100") && raw.length() > 4) {
            return "https://t.me/c/" + raw.substring(4) + "/" + messageId;
        }
        // Telegram message links cannot be constructed reliably for a basic group without its public username.
        return "https://t.me/c/" + raw.replaceFirst("^-", "") + "/" + messageId;
    }

    private void sendMessageWithMarkup(long chatId, String text, InlineKeyboardMarkup markup) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        telegramExecutor.execute(() -> {
            try {
                execute(msg);
            } catch (TelegramApiException e) {
                System.err.println("[BOT] Không gửi được tin nhắn có nút: " + e.getMessage());
            }
        });
    }

    private long parseMoney(String raw) {
        if (raw == null) return 0L;
        String input = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (input.isEmpty()) return 0L;

        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("^([0-9]+(?:\\.[0-9]+)?)([KMBT]?)$")
                    .matcher(input);
            if (matcher.matches() && !matcher.group(2).isEmpty()) {
                java.math.BigDecimal amount = new java.math.BigDecimal(matcher.group(1));
                java.math.BigDecimal multiplier = switch (matcher.group(2)) {
                    case "K" -> java.math.BigDecimal.valueOf(1_000L);
                    case "M" -> java.math.BigDecimal.valueOf(1_000_000L);
                    case "B" -> java.math.BigDecimal.valueOf(1_000_000_000L);
                    case "T" -> java.math.BigDecimal.valueOf(1_000_000_000_000L);
                    default -> java.math.BigDecimal.ONE;
                };
                return amount.multiply(multiplier).longValueExact();
            }

            // Số nguyên đầy đủ; chấp nhận dấu phân tách hàng nghìn dạng 1,000 / 1.000 / 1_000.
            String digits = input.replace(",", "").replace(".", "").replace("_", "");
            if (!digits.matches("[0-9]+")) return 0L;
            return Long.parseLong(digits);
        } catch (ArithmeticException | NumberFormatException e) {
            return 0L;
        }
    }

    private void sendMessage(long chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        msg.setParseMode("HTML");
        telegramExecutor.execute(() -> {
            try {
                execute(msg);
            } catch (TelegramApiException e) {
                System.err.println("[BOT] Không gửi được tin nhắn: " + e.getMessage());
            }
        });
    }

    private void sendMessageSync(long chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        msg.setParseMode("HTML");
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            System.err.println("[BOT] Không gửi được tin nhắn: " + e.getMessage());
        }
    }

    private void deleteMessage(long chatId, Integer messageId) {
        if (messageId == null) return;
        DeleteMessage request = new DeleteMessage();
        request.setChatId(String.valueOf(chatId));
        request.setMessageId(messageId);
        telegramExecutor.execute(() -> {
            try {
                execute(request);
            } catch (TelegramApiException e) {
                System.err.println("[BOT] Không xóa được tin nhắn: " + e.getMessage());
            }
        });
    }

    private void answerAlert(String queryId, String text) {
        if (queryId == null || queryId.isBlank()) return;
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(queryId);
        answer.setText(text);
        // Tách phản hồi nút khỏi hàng đợi gửi/sửa tin nhắn để nút phản hồi nhanh hơn.
        // Giữ nguyên showAlert=true để nút XEM BÀI vẫn hiện popup như trước.
        answer.setShowAlert(true);
        callbackExecutor.execute(() -> {
            try {
                execute(answer);
            } catch (TelegramApiException e) {
                System.err.println("[BOT] Không trả lời được callback: " + e.getMessage());
            }
        });
    }

    private String formatDetailedMoney(long amount) {
        return String.format(java.util.Locale.US, "%,d", amount).replace(',', '.');
    }

    private String formatShortMoney(long amount) {
        if (amount >= 1_000_000_000L) {
            return compactMoney(amount / 1_000_000_000.0, "B");
        }
        if (amount >= 1_000_000L) {
            return compactMoney(amount / 1_000_000.0, "M");
        }
        if (amount >= 1_000L) {
            return compactMoney(amount / 1_000.0, "K");
        }
        return String.valueOf(amount);
    }

    private String compactMoney(double value, String suffix) {
        java.text.DecimalFormat df = new java.text.DecimalFormat("0.##",
                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US));
        return df.format(value) + suffix;
    }

    private InlineKeyboardMarkup getDealerKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(createBtn("👑 CẦM CÁI", "take_dealer")));
        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardMarkup getBettingKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (long amount : BET_AMOUNTS) {
            rows.add(List.of(
                    createBtn("🔴 TÀI " + formatShortMoney(amount), "bet:T:" + amount),
                    createBtn("🔵 XỈU " + formatShortMoney(amount), "bet:X:" + amount)
            ));
        }
        rows.add(List.of(createBtn("🎲 TUNG XÚC XẮC", "roll")));
        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardButton createBtn(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(callbackData);
        return button;
    }
}
