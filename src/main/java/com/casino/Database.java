package com.casino;

import java.sql.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.*;

/** Supabase/PostgreSQL access. Environment variables override the legacy DB_URL fallback. */
public final class Database {
    private Database() {}

    // Biến DB_URL cũ được giữ làm giá trị mặc định theo yêu cầu.
    // Khuyến nghị dùng SUPABASE_JDBC_URL trên Render để không lưu mật khẩu trong mã nguồn.
    private static final String DB_URL = "jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require&user=postgres.jxwngfsvfvxayueujorg&password=IO0QrEg008AKJRCY";

    private static String databaseUrl() {
        String url = firstNonBlank(
                System.getenv("SUPABASE_JDBC_URL"),
                System.getenv("DATABASE_URL"),
                DB_URL
        );
        url = url.trim();
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("SUPABASE_JDBC_URL/DB_URL phải bắt đầu bằng jdbc:postgresql://; không dùng URL API https:// của Supabase.");
        }
        return url;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static Connection openConnection() throws SQLException {
        try {
            return DriverManager.getConnection(databaseUrl());
        } catch (IllegalStateException e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    /** Called once during application startup. Creates missing tables and migrates columns on old schemas. */
    public static void initDb() {
        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "user_id BIGINT PRIMARY KEY, " +
                    "username TEXT, " +
                    "firstname TEXT DEFAULT 'Player', " +
                    "balance BIGINT NOT NULL DEFAULT 500000000, " +
                    "is_vip INT NOT NULL DEFAULT 0)");
            stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS username TEXT");
            stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS firstname TEXT DEFAULT 'Player'");
            stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS balance BIGINT NOT NULL DEFAULT 500000000");
            stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS is_vip INT NOT NULL DEFAULT 0");
            stmt.execute("CREATE TABLE IF NOT EXISTS temporary_vip (" +
                    "user_id BIGINT PRIMARY KEY, " +
                    "expires_at BIGINT NOT NULL DEFAULT 0, " +
                    "last_claim_date DATE NOT NULL)");
            stmt.execute("ALTER TABLE temporary_vip ADD COLUMN IF NOT EXISTS expires_at BIGINT NOT NULL DEFAULT 0");
            stmt.execute("ALTER TABLE temporary_vip ADD COLUMN IF NOT EXISTS last_claim_date DATE");
            // Makes older rows from a partial legacy migration usable while retaining a NOT NULL date.
            stmt.execute("UPDATE temporary_vip SET last_claim_date = CURRENT_DATE WHERE last_claim_date IS NULL");
            stmt.execute("ALTER TABLE temporary_vip ALTER COLUMN last_claim_date SET NOT NULL");
        } catch (SQLException e) {
            throw new IllegalStateException("Không thể kết nối/khởi tạo Supabase. Kiểm tra SUPABASE_JDBC_URL và quyền database.", e);
        }
    }

    public static void ensureUser(long userId, String username, String firstname) {
        String sql = "INSERT INTO users (user_id, username, firstname, balance, is_vip) VALUES (?, ?, ?, 500000000, 0) " +
                "ON CONFLICT (user_id) DO UPDATE SET username = EXCLUDED.username, firstname = EXCLUDED.firstname";
        try (Connection conn = openConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, username == null ? "" : username);
            pstmt.setString(3, firstname == null || firstname.isBlank() ? "Player" : firstname);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Không thể lưu thông tin người dùng " + userId + ": " + e.getMessage());
        }
    }

    public static long getBalance(long userId) {
        try (Connection conn = openConnection(); PreparedStatement pstmt = conn.prepareStatement(
                "SELECT balance FROM users WHERE user_id = ?")) {
            pstmt.setLong(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getLong("balance");
            }
        } catch (SQLException e) {
            System.err.println("[DB] Không đọc được số dư userId=" + userId + ": " + e.getMessage());
        }
        return 0L;
    }

    public static void changeBalance(long userId, long amount) {
        if (!changeBalanceChecked(userId, amount)) {
            System.err.println("[BALANCE] Thay đổi số dư thất bại userId=" + userId + ", amount=" + amount);
        }
    }

    /** Applies one change only if the row exists and the resulting balance will not be negative. */
    public static boolean changeBalanceChecked(long userId, long amount) {
        String sql = "UPDATE users SET balance = balance + ? WHERE user_id = ? AND (balance + ?) >= 0";
        try (Connection conn = openConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, amount);
            pstmt.setLong(2, userId);
            pstmt.setLong(3, amount);
            return pstmt.executeUpdate() == 1;
        } catch (SQLException e) {
            System.err.println("[BALANCE] Lỗi cập nhật số dư userId=" + userId + ", amount=" + amount + ": " + e.getMessage());
            return false;
        }
    }

    /** Atomically applies multiple balance deltas, locking rows in a consistent order and forbidding negative balances. */
    public static boolean applyBalanceChangesAtomically(Map<Long, Long> changes) {
        if (changes == null || changes.isEmpty()) return true;
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                Map<Long, Long> current = lockBalances(conn, changes.keySet());
                Map<Long, Long> next = new LinkedHashMap<>();
                for (Map.Entry<Long, Long> entry : changes.entrySet()) {
                    Long balance = current.get(entry.getKey());
                    if (balance == null) throw new SQLException("Không tìm thấy userId=" + entry.getKey());
                    long updated = Math.addExact(balance, entry.getValue());
                    if (updated < 0) {
                        conn.rollback();
                        return false;
                    }
                    next.put(entry.getKey(), updated);
                }
                writeBalances(conn, next);
                conn.commit();
                return true;
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException) throw (SQLException) e;
                System.err.println("[BALANCE] Giao dịch số dư bị hủy: " + e.getMessage());
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[BALANCE] Không thể áp dụng giao dịch nhiều số dư: " + e.getMessage());
            return false;
        }
    }

    /** Atomic player-to-player transfer. No money is deducted unless the receiver can be credited in the same transaction. */
    public static boolean transferBalance(long fromUserId, long toUserId, long amount) {
        if (fromUserId == toUserId || amount <= 0) return false;
        Map<Long, Long> delta = new LinkedHashMap<>();
        delta.put(fromUserId, -amount);
        delta.put(toUserId, amount);
        return applyBalanceChangesAtomically(delta);
    }

    /**
     * Atomically applies ranking refunds/results and pays each eligible end-of-round fine to the winner.
     * Fines that a player cannot fully afford are skipped. The returned map holds the fines actually paid.
     */
    public static Map<Long, Long> settleTienLen(long winnerId, Map<Long, Long> rankingChanges,
                                                Map<Long, Long> finesByPlayer) throws SQLException {
        if (rankingChanges == null || rankingChanges.isEmpty() || !rankingChanges.containsKey(winnerId)) {
            throw new IllegalArgumentException("Thiếu thay đổi số dư xếp hạng hoặc người thắng");
        }
        Map<Long, Long> changes = new LinkedHashMap<>(rankingChanges);
        if (finesByPlayer != null) {
            for (Map.Entry<Long, Long> e : finesByPlayer.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue() < 0) {
                    throw new IllegalArgumentException("Khoản phạt không hợp lệ");
                }
                changes.putIfAbsent(e.getKey(), 0L);
            }
        }
        changes.putIfAbsent(winnerId, 0L);

        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                Map<Long, Long> before = lockBalances(conn, changes.keySet());
                Map<Long, Long> next = new LinkedHashMap<>();
                for (Map.Entry<Long, Long> e : changes.entrySet()) {
                    Long bal = before.get(e.getKey());
                    if (bal == null) throw new SQLException("Không tìm thấy userId=" + e.getKey());
                    long postRank = Math.addExact(bal, rankingChanges.getOrDefault(e.getKey(), 0L));
                    if (postRank < 0) throw new SQLException("Quyết toán sẽ làm số dư âm userId=" + e.getKey());
                    next.put(e.getKey(), postRank);
                }
                Map<Long, Long> paid = new LinkedHashMap<>();
                if (finesByPlayer != null) {
                    for (Map.Entry<Long, Long> e : finesByPlayer.entrySet()) {
                        long payer = e.getKey();
                        long fine = e.getValue();
                        if (payer == winnerId || fine <= 0) continue;
                        long payerBalance = next.getOrDefault(payer, 0L);
                        if (payerBalance < fine) continue;
                        next.put(payer, payerBalance - fine);
                        next.put(winnerId, Math.addExact(next.get(winnerId), fine));
                        paid.put(payer, fine);
                    }
                }
                writeBalances(conn, next);
                conn.commit();
                return paid;
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException) throw (SQLException) e;
                throw new SQLException("Không thể tính toán giao dịch Tiến Lên", e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static Map<Long, Long> lockBalances(Connection conn, Collection<Long> userIds) throws SQLException {
        List<Long> ids = new ArrayList<>(new HashSet<>(userIds));
        ids.sort(Long::compareTo);
        Map<Long, Long> balances = new LinkedHashMap<>();
        if (ids.isEmpty()) return balances;
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT user_id, balance FROM users WHERE user_id IN (" + placeholders + ") ORDER BY user_id FOR UPDATE";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) pstmt.setLong(i + 1, ids.get(i));
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) balances.put(rs.getLong("user_id"), rs.getLong("balance"));
            }
        }
        return balances;
    }

    private static void writeBalances(Connection conn, Map<Long, Long> balances) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("UPDATE users SET balance = ? WHERE user_id = ?")) {
            for (Map.Entry<Long, Long> e : balances.entrySet()) {
                pstmt.setLong(1, e.getValue());
                pstmt.setLong(2, e.getKey());
                if (pstmt.executeUpdate() != 1) throw new SQLException("Không cập nhật được userId=" + e.getKey());
            }
        }
    }

    /** Reset balances and permanent/temporary VIP except the supplied administrator IDs, atomically. */
    public static int resetAllBalancesAndVipExcept(long... adminIds) throws SQLException {
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                String condition = buildNotInCondition("user_id", adminIds == null ? 0 : adminIds.length);
                String sql = "UPDATE users SET balance = 1000000000, is_vip = 0 WHERE " + condition;
                int updated;
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    bindIds(pstmt, adminIds);
                    updated = pstmt.executeUpdate();
                }
                String vipSql = "UPDATE temporary_vip SET expires_at = 0 WHERE " + condition;
                try (PreparedStatement pstmt = conn.prepareStatement(vipSql)) {
                    bindIds(pstmt, adminIds);
                    pstmt.executeUpdate();
                } catch (SQLException noVipTable) {
                    // If the migration was not yet applied, roll back instead of reporting a partial reset.
                    throw noVipTable;
                }
                conn.commit();
                return updated;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static String buildNotInCondition(String column, int count) {
        if (count <= 0) return "1 = 1";
        return column + " NOT IN (" + String.join(",", Collections.nCopies(count, "?")) + ")";
    }

    private static void bindIds(PreparedStatement pstmt, long[] ids) throws SQLException {
        if (ids == null) return;
        for (int i = 0; i < ids.length; i++) pstmt.setLong(i + 1, ids[i]);
    }

    public static boolean isVip(long userId) {
        try (Connection conn = openConnection(); PreparedStatement pstmt = conn.prepareStatement(
                "SELECT is_vip FROM users WHERE user_id = ?")) {
            pstmt.setLong(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt("is_vip") == 1;
            }
        } catch (SQLException e) {
            System.err.println("[VIP] Không đọc được VIP userId=" + userId + ": " + e.getMessage());
            return false;
        }
    }

    public static void setVipStatus(long userId, boolean status) {
        try (Connection conn = openConnection(); PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE users SET is_vip = ? WHERE user_id = ?")) {
            pstmt.setInt(1, status ? 1 : 0);
            pstmt.setLong(2, userId);
            if (pstmt.executeUpdate() != 1) System.err.println("[VIP] Không tìm thấy userId=" + userId + " để cập nhật VIP");
        } catch (SQLException e) {
            System.err.println("[VIP] Không cập nhật được trạng thái VIP: " + e.getMessage());
        }
    }

    /** Claims /yeubi once per Vietnam calendar day, using a single PostgreSQL upsert. */
    public static boolean claimTemporaryVip(long userId, LocalDate today, long expiresAtMillis) throws SQLException {
        String sql = "INSERT INTO temporary_vip (user_id, expires_at, last_claim_date) VALUES (?, ?, ?) " +
                "ON CONFLICT (user_id) DO UPDATE SET expires_at = EXCLUDED.expires_at, " +
                "last_claim_date = EXCLUDED.last_claim_date " +
                "WHERE temporary_vip.last_claim_date IS DISTINCT FROM EXCLUDED.last_claim_date";
        try (Connection conn = openConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setLong(2, expiresAtMillis);
            pstmt.setDate(3, java.sql.Date.valueOf(today));
            return pstmt.executeUpdate() > 0;
        }
    }

    public static long getTemporaryVipUntil(long userId) throws SQLException {
        try (Connection conn = openConnection(); PreparedStatement pstmt = conn.prepareStatement(
                "SELECT expires_at FROM temporary_vip WHERE user_id = ?")) {
            pstmt.setLong(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getLong("expires_at") : 0L;
            }
        }
    }

    public static int deactivateAllTemporaryVip() throws SQLException {
        try (Connection conn = openConnection(); PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE temporary_vip SET expires_at = 0 WHERE expires_at > ?")) {
            pstmt.setLong(1, System.currentTimeMillis());
            return pstmt.executeUpdate();
        }
    }

    public static String getTopText(long[] adminIds) {
        StringBuilder sb = new StringBuilder("🏆 <b>BẢNG XẾP HẠNG TÀI SẢN</b>\n\n");
        try (Connection conn = openConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT user_id, firstname, balance FROM users ORDER BY balance DESC LIMIT 100")) {
            int rank = 1;
            boolean hasData = false;
            while (rs.next()) {
                long userId = rs.getLong("user_id");
                boolean admin = false;
                if (adminIds != null) for (long id : adminIds) if (id == userId) { admin = true; break; }
                if (admin) continue;
                hasData = true;
                String name = rs.getString("firstname");
                long balance = rs.getLong("balance");
                String medal = rank == 1 ? "🥇 " : rank == 2 ? "🥈 " : rank == 3 ? "🥉 " : rank + ". ";
                sb.append(medal).append("<a href=\"tg://user?id=").append(userId).append("\">")
                        .append(escapeHtml(name == null || name.isBlank() ? "Player" : name))
                        .append("</a> [").append(formatDetailedMoney(balance)).append(" đ.]\n");
                rank++;
                if (rank > 10) break;
            }
            if (!hasData) sb.append("Chưa có dữ liệu người chơi trong hệ thống.");
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi tải bảng xếp hạng: " + e.getMessage());
            return "❌ Lỗi khi tải bảng xếp hạng từ cơ sở dữ liệu.";
        }
        return sb.toString();
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String formatDetailedMoney(long amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(',');
        return new DecimalFormat("#,###", symbols).format(amount);
    }
}
