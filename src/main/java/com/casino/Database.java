package com.casino;

import java.sql.*;

public class Database {
    private static final String DB_URL = "jdbc:sqlite:bot.db";

    public static void initDb() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");

            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "user_id INTEGER PRIMARY KEY, " +
                    "username TEXT, " +
                    "first_name TEXT, " +
                    "display_name TEXT, " +
                    "balance INTEGER NOT NULL DEFAULT 1000000000, " +
                    "is_vip INTEGER DEFAULT 0)");

            stmt.execute("CREATE TABLE IF NOT EXISTS game_history (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "result TEXT NOT NULL, " +
                    "total INTEGER NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void ensureUser(long userId, String username, String firstName) {
        String displayName = (firstName != null && !firstName.isBlank()) ? firstName : "Người dùng";
        String selectSql = "SELECT user_id FROM users WHERE user_id = ?";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(selectSql)) {

            pstmt.setLong(1, userId);
            ResultSet rs = pstmt.executeQuery();

            if (!rs.next()) {
                String insertSql = "INSERT INTO users (user_id, username, first_name, display_name, balance, is_vip) VALUES (?, ?, ?, ?, 1000000000, 0)";
                try (PreparedStatement pInsert = conn.prepareStatement(insertSql)) {
                    pInsert.setLong(1, userId);
                    pInsert.setString(2, username);
                    pInsert.setString(3, firstName != null ? firstName : "");
                    pInsert.setString(4, displayName);
                    pInsert.executeUpdate();
                }
            } else {
                String updateSql = "UPDATE users SET display_name = ?, first_name = ?, username = ? WHERE user_id = ?";
                try (PreparedStatement pUpdate = conn.prepareStatement(updateSql)) {
                    pUpdate.setString(1, displayName);
                    pUpdate.setString(2, firstName != null ? firstName : "");
                    pUpdate.setString(3, username);
                    pUpdate.setLong(4, userId);
                    pUpdate.executeUpdate();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static synchronized long getBalance(long userId) {
        String sql = "SELECT balance FROM users WHERE user_id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
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
        String sql = "UPDATE users SET balance = balance + ? WHERE user_id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, amount);
            pstmt.setLong(2, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static synchronized boolean isVip(long userId) {
        String sql = "SELECT is_vip FROM users WHERE user_id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
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
        String sql = "UPDATE users SET is_vip = ? WHERE user_id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, status ? 1 : 0);
            pstmt.setLong(2, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static synchronized String getTopText(long[] adminIds) {
        StringBuilder sb = new StringBuilder("🏆 <b>BẢNG XẾP HẠNG</b> 🏆\n\n");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < adminIds.length; i++) {
            placeholders.append("?");
            if (i < adminIds.length - 1) placeholders.append(",");
        }

        String sql = "SELECT display_name, balance, is_vip FROM users WHERE user_id NOT IN (" + placeholders + ") ORDER BY balance DESC LIMIT 20";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < adminIds.length; i++) {
                pstmt.setLong(i + 1, adminIds[i]);
            }
            ResultSet rs = pstmt.executeQuery();
            int idx = 1;
            while (rs.next()) {
                String icon = (idx == 1) ? "🥇" : (idx == 2) ? "🥈" : (idx == 3) ? "🥉" : String.valueOf(idx);
                String vip = rs.getInt("is_vip") == 1 ? " ⭐VIP" : "";
                long bal = rs.getLong("balance");
                sb.append(String.format("【 %s 】· <b>%s%s</b> &lt;%,d đ.&gt;\n", icon, rs.getString("display_name"), vip, bal).replace(",", "."));
                idx++;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return sb.toString();
    }
}
