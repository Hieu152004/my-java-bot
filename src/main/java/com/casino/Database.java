package com.casino;

import java.sql.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class Database {
    /*
     * IMPORTANT:
     * - Render nên đặt SUPABASE_URL là JDBC Session Pooler URL port 5432.
     * - Không tạo connection mới cho từng thao tác nữa: dùng pool 8 connection.
     */
    private static final String DB_URL = buildDbUrl();

    private static String buildDbUrl() {
        String url = System.getenv("SUPABASE_URL");
        if (url == null || url.isBlank()) {
            url = "postgresql://postgres.jxwngfsvfvxayueujorg:IO0QrEg008AKJRCY@aws-0-ap-south-1.pooler.supabase.com:5432/postgres"
                    + System.getenv().getOrDefault("SUPABASE_DB_PASSWORD", "");
        }
        String sep = url.contains("?") ? "&" : "?";
        if (!url.contains("connectTimeout=")) url += sep + "connectTimeout=4";
        if (!url.contains("socketTimeout=")) url += "&socketTimeout=8";
        if (!url.contains("tcpKeepAlive=")) url += "&tcpKeepAlive=true";
        return url;
    }

    private static final int POOL_SIZE = 12;
    private static final BlockingQueue<Connection> CONNECTION_POOL = new ArrayBlockingQueue<>(POOL_SIZE);

    static {
        DriverManager.setLoginTimeout(4);
        initDb();
        warmUpPool();
    }

    private static void warmUpPool() {
        for (int i = 0; i < POOL_SIZE; i++) {
            try {
                Connection c = DriverManager.getConnection(DB_URL);
                c.setAutoCommit(true);
                CONNECTION_POOL.offer(c);
            } catch (SQLException e) {
                System.err.println("[DB] Không thể khởi tạo connection #" + (i + 1) + ": " + e.getMessage());
                break;
            }
        }
    }

    private static Connection borrowConnection() throws SQLException {
        Connection c = CONNECTION_POOL.poll();
        if (c != null) {
            try {
                // Không gọi isValid(2) ở mỗi thao tác: đây có thể là một round-trip mạng
                // và chính nó gây cảm giác chậm vài giây khi Supabase/Render dao động.
                if (!c.isClosed()) return c;
            } catch (SQLException ignored) {}
            try { c.close(); } catch (SQLException ignored) {}
        }
        return DriverManager.getConnection(DB_URL);
    }

    private static void returnConnection(Connection c) {
        if (c == null) return;
        try {
            if (c.isClosed()) return;
            c.setAutoCommit(true);
            if (!CONNECTION_POOL.offer(c)) c.close();
        } catch (Exception e) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    public static void initDb() {
        Connection conn = null;
        try {
            conn = borrowConnection();
            try (Statement stmt = conn.createStatement()) {
                String sql = "CREATE TABLE IF NOT EXISTS users (" +
                        "user_id BIGINT PRIMARY KEY, " +
                        "username TEXT, " +
                        "firstname TEXT, " +
                        "balance BIGINT DEFAULT 500000000, " +
                        "is_vip INT DEFAULT 0)";
                stmt.execute(sql);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            returnConnection(conn);
        }
    }

    public static void ensureUser(long userId, String username, String firstname) {
        Connection conn = null;
        try {
            conn = borrowConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO users (user_id, username, firstname, balance, is_vip) VALUES (?, ?, ?, 500000000, 0) " +
                    "ON CONFLICT (user_id) DO UPDATE SET username = EXCLUDED.username, firstname = EXCLUDED.firstname")) {
                pstmt.setLong(1, userId);
                pstmt.setString(2, username != null ? username : "");
                pstmt.setString(3, firstname != null && !firstname.isEmpty() ? firstname : "Player");
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            returnConnection(conn);
        }
    }

    public static long getBalance(long userId) {
        Connection conn = null;
        try {
            conn = borrowConnection();
            try (PreparedStatement pstmt = conn.prepareStatement("SELECT balance FROM users WHERE user_id = ?")) {
                pstmt.setLong(1, userId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) return rs.getLong("balance");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            returnConnection(conn);
        }
        return 0;
    }

    public static void changeBalance(long userId, long amount) {
        Connection conn = null;
        try {
            conn = borrowConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE users SET balance = balance + ? WHERE user_id = ?")) {
                pstmt.setLong(1, amount);
                pstmt.setLong(2, userId);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            returnConnection(conn);
        }
    }

    /** Trừ/cộng số dư an toàn. Delta âm chỉ thành công khi đủ số dư. */
    public static boolean tryChangeBalance(long userId, long delta) {
        Connection conn = null;
        try {
            conn = borrowConnection();
            String sql = delta < 0
                    ? "UPDATE users SET balance = balance + ? WHERE user_id = ? AND balance >= ?"
                    : "UPDATE users SET balance = balance + ? WHERE user_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, delta);
                pstmt.setLong(2, userId);
                if (delta < 0) pstmt.setLong(3, -delta);
                return pstmt.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        } finally {
            returnConnection(conn);
        }
    }

    /**
     * Trừ cược sàn cho cả bàn trong một transaction.
     * Nếu một người thiếu tiền -> rollback toàn bộ.
     */
    public static boolean tryDeductBalances(List<Long> userIds, long amount) {
        if (userIds == null || userIds.isEmpty() || amount <= 0) return false;

        Connection conn = null;
        try {
            conn = borrowConnection();
            conn.setAutoCommit(false);
            String sql = "UPDATE users SET balance = balance - ? WHERE user_id = ? AND balance >= ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (long userId : userIds) {
                    pstmt.setLong(1, amount);
                    pstmt.setLong(2, userId);
                    pstmt.setLong(3, amount);
                    if (pstmt.executeUpdate() != 1) {
                        conn.rollback();
                        return false;
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        } finally {
            returnConnection(conn);
        }
    }

    public static boolean isVip(long userId) {
        Connection conn = null;
        try {
            conn = borrowConnection();
            try (PreparedStatement pstmt = conn.prepareStatement("SELECT is_vip FROM users WHERE user_id = ?")) {
                pstmt.setLong(1, userId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    return rs.next() && rs.getInt("is_vip") == 1;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        } finally {
            returnConnection(conn);
        }
    }

    public static void setVipStatus(long userId, boolean status) {
        Connection conn = null;
        try {
            conn = borrowConnection();
            try (PreparedStatement pstmt = conn.prepareStatement("UPDATE users SET is_vip = ? WHERE user_id = ?")) {
                pstmt.setInt(1, status ? 1 : 0);
                pstmt.setLong(2, userId);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            returnConnection(conn);
        }
    }

    public static String getTopText(long[] adminIds) {
        StringBuilder sb = new StringBuilder("🏆 <b>BẢNG XẾP HẠNG TÀI SẢN</b>\n\n");
        Connection conn = null;
        try {
            conn = borrowConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT user_id, firstname, balance FROM users ORDER BY balance DESC LIMIT 30")) {
                int rank = 1;
                boolean hasData = false;
                while (rs.next()) {
                    long userId = rs.getLong("user_id");
                    boolean isAdmin = false;
                    if (adminIds != null) {
                        for (long adminId : adminIds) {
                            if (adminId == userId) { isAdmin = true; break; }
                        }
                    }
                    if (isAdmin) continue;
                    hasData = true;
                    String name = rs.getString("firstname");
                    long balance = rs.getLong("balance");
                    String medal = rank == 1 ? "🥇 " : rank == 2 ? "🥈 " : rank == 3 ? "🥉 " : rank + ". ";
                    sb.append(medal).append("<a href=\"tg://user?id=").append(userId).append("\">")
                      .append(name != null && !name.isEmpty() ? name : "Player")
                      .append("</a> [").append(formatDetailedMoney(balance)).append(" đ.]\n");
                    if (++rank > 10) break;
                }
                if (!hasData) sb.append("Chưa có dữ liệu người chơi trong hệ thống.");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Lỗi khi tải bảng xếp hạng từ cơ sở dữ liệu.";
        } finally {
            returnConnection(conn);
        }
        return sb.toString();
    }

    private static String formatDetailedMoney(long amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.getDefault());
        symbols.setGroupingSeparator(',');
        return new DecimalFormat("#,###", symbols).format(amount);
    }
}
