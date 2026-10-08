package com.casino;

import java.sql.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class Database {
    private static final String DB_URL = System.getenv("SUPABASE_URL") != null ? 
        System.getenv("SUPABASE_URL") : 
        "jdbc:postgresql://db.jxwngfsvfvxayueujorg.supabase.co:6543/postgres?sslmode=require&user=postgres&password=IO0QrEg008AKJRCY";
    static {
        initDb();
    }

    public static synchronized void initDb() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            String sql = "CREATE TABLE IF NOT EXISTS users (" +
                    "user_id BIGINT PRIMARY KEY, " +
                    "username TEXT, " +
                    "firstname TEXT, " +
                    "balance BIGINT DEFAULT 500000000, " +
                    "is_vip INT DEFAULT 0)";
            stmt.execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void ensureUser(long userId, String username, String firstname) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO users (user_id, username, firstname, balance, is_vip) VALUES (?, ?, ?, 500000000, 0) " +
                     "ON CONFLICT (user_id) DO NOTHING")) {
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
             ResultSet rs = stmt.executeQuery("SELECT user_id, firstname, balance FROM users ORDER BY balance DESC LIMIT 20")) {
            
            int rank = 1;
            boolean hasData = false;
            while (rs.next()) {
                long userId = rs.getLong("user_id");
                boolean isAdmin = false;
                for (long adminId : adminIds) {
                    if (adminId == userId) {
                        isAdmin = true;
                        break;
                    }
                }
                if (isAdmin) continue; // Bỏ qua tài khoản admin trong bảng xếp hạng

                hasData = true;
                String name = rs.getString("firstname");
                long balance = rs.getLong("balance");

                String medal = "";
                if (rank == 1) medal = "🥇 ";
                else if (rank == 2) medal = "🥈 ";
                else if (rank == 3) medal = "🥉 ";
                else medal = rank + ". ";

                sb.append(medal).append("<a href=\"tg://user?id=").append(userId).append("\">").append(name != null ? name : "Player")
                  .append("</a> [").append(formatDetailedMoney(balance)).append(" đ.]\n");
                
                rank++;
                if (rank > 10) break; // Chỉ lấy tối đa top 10 người chơi thực tế
            }
            if (!hasData) {
                sb.append("Chưa có dữ liệu người chơi trong hệ thống.");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Lỗi khi tải bảng xếp hạng từ cơ sở dữ liệu.";
        }
        return sb.toString();
    }

    private static String formatDetailedMoney(long amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.getDefault());
        symbols.setGroupingSeparator(',');
        DecimalFormat formatter = new DecimalFormat("#,###", symbols);
        return formatter.format(amount);
    }
}
