package com.casino;

import java.sql.*;

public class Database {
    private static final String DB_URL = "jdbc:sqlite:casino.db";

    static {
        initDb();
    }

    public static synchronized void initDb() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            String sql = "CREATE TABLE IF NOT EXISTS users (" +
                    "user_id LONG PRIMARY KEY, " +
                    "username TEXT, " +
                    "firstname TEXT, " +
                    "balance LONG DEFAULT 500000000, " +
                    "is_vip INTEGER DEFAULT 0)";
            stmt.execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void ensureUser(long userId, String username, String firstname) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT OR IGNORE INTO users (user_id, username, firstname, balance, is_vip) VALUES (?, ?, ?, 500000000, 0)")) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, username != null ? username : "");
            pstmt.setString(3, firstname != null ? firstname : "Player");
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static synchronized long getBalance(long userId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement("SELECT balance FROM users WHERE user_id = ?")) {
            pstmt.setLong(1, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("balance");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public static synchronized void changeBalance(long userId, long amount) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(
                     "UPDATE users SET balance = balance + ? WHERE user_id = ?")) {
            pstmt.setLong(1, amount);
            pstmt.setLong(2, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static synchronized boolean isVip(long userId) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement("SELECT is_vip FROM users WHERE user_id = ?")) {
            pstmt.setLong(1, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("is_vip") == 1;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static synchronized void setVipStatus(long userId, boolean status) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement("UPDATE users SET is_vip = ? WHERE user_id = ?")) {
            pstmt.setInt(1, status ? 1 : 0);
            pstmt.setLong(2, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static synchronized String getTopText(long[] adminIds) {
        StringBuilder sb = new StringBuilder("🏆 <b>BẢNG XẾP HẠNG TÀI SẢN</b>\n\n");
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT user_id, firstname, balance FROM users ORDER BY balance DESC LIMIT 10")) {
            
            int rank = 1;
            while (rs.next()) {
                long userId = rs.getLong("user_id");
                boolean isAdmin = false;
                for (long adminId : adminIds) {
                    if (adminId == userId) {
                        isAdmin = true;
                        break;
                    }
                }
                if (isAdmin) continue;

                String name = rs.getString("firstname");
                long balance = rs.getLong("balance");

                String medal = "";
                if (rank == 1) medal = "🥇 ";
                else if (rank == 2) medal = "🥈 ";
                else if (rank == 3) medal = "🥉 ";
                else medal = rank + ". ";

                sb.append(medal).append("<a href=\"tg://user?id=").append(userId).append("\">").append(name)
                  .append("</a>: ").append(formatMoney(balance)).append("\n");
                rank++;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return sb.toString();
    }

    private static String formatMoney(long amount) {
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
}
