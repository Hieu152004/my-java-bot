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
        // Render: đặt SUPABASE_URL bằng chuỗi PostgreSQL của Supabase.
        // Có thể dùng:
        // postgresql://USER:PASSWORD@HOST:5432/postgres
        // hoặc JDBC URL:
        // jdbc:postgresql://HOST:5432/postgres?user=...&password=...
        String url = System.getenv("SUPABASE_URL");

        if (url == null || url.isBlank()) {
            String password = System.getenv("SUPABASE_DB_PASSWORD");
            if (password != null && !password.isBlank()) {
                url = "jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres"
                        + "?sslmode=require"
                        + "&user=postgres.jxwngfsvfvxayueujorg"
                        + "&password=" + java.net.URLEncoder.encode(password.trim(), java.nio.charset.StandardCharsets.UTF_8);
            } else {
                // Fallback để bot không chết ngay khi Render chưa được thêm Environment Variable.
                // KHUYẾN NGHỊ: đặt SUPABASE_URL trên Render và xóa fallback này sau khi deploy.
                url = "jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres"
                        + "?sslmode=require"
                        + "&user=postgres.jxwngfsvfvxayueujorg"
                        + "&password=IO0QrEg008AKJRCY";
            }
        } else {
            url = url.trim();
            if (url.startsWith("postgresql://")) {
            // Supabase đưa URI dạng postgresql://, PostgreSQL JDBC cần jdbc:postgresql://
                url = "jdbc:" + url;
            }
        }

        String sep = url.contains("?") ? "&" : "?";
        if (!url.contains("sslmode=")) url += sep + "sslmode=require";
        sep = url.contains("?") ? "&" : "?";
        if (!url.contains("connectTimeout=")) url += sep + "connectTimeout=4";
        sep = url.contains("?") ? "&" : "?";
        if (!url.contains("socketTimeout=")) url += sep + "socketTimeout=8";
        sep = url.contains("?") ? "&" : "?";
        if (!url.contains("tcpKeepAlive=")) url += sep + "tcpKeepAlive=true";
        return url;
    }

    private static final int POOL_SIZE = 4;
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
                if (!c.isClosed() && c.isValid(1)) return c;
            } catch (SQLException ignored) {}
            try { c.close(); } catch (SQLException ignored) {}
        }

        // Không tạo connection mới liên tục khi pool đang bận.
        // Chờ tối đa 2 giây để lấy lại connection, tránh connection storm.
        try {
            c = CONNECTION_POOL.poll(2, java.util.concurrent.TimeUnit.SECONDS);
            if (c != null) {
                try {
                    if (!c.isClosed() && c.isValid(1)) return c;
                } catch (SQLException ignored) {}
                try { c.close(); } catch (SQLException ignored) {}
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private static boolean isTransient(SQLException e) {
        String m = e.getMessage();
        if (m == null) return true;
        String x = m.toLowerCase(Locale.ROOT);
        return x.contains("connection") || x.contains("timeout") || x.contains("network") || x.contains("closed");
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
        SQLException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Connection conn = null;
            try {
                conn = borrowConnection();
                try (Statement stmt = conn.createStatement()) {
                    stmt.setQueryTimeout(5);
                    try (ResultSet rs = stmt.executeQuery("SELECT user_id, firstname, balance FROM users ORDER BY balance DESC LIMIT 30")) {
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
                }
                return sb.toString();
            } catch (SQLException e) {
                last = e;
                if (!isTransient(e) || attempt == 2) break;
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            } finally {
                returnConnection(conn);
            }
        }
        if (last != null) last.printStackTrace();
        return "❌ Không thể kết nối cơ sở dữ liệu lúc này. Vui lòng thử lại sau 1 giây.";
    }

    private static String formatDetailedMoney(long amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.getDefault());
        symbols.setGroupingSeparator(',');
        return new DecimalFormat("#,###", symbols).format(amount);
    }
}
