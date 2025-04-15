package org.serverplugin.emoniaplugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class Emoniaplugin extends JavaPlugin {
    private Connection connection;
    private final Map<Player, Map<Integer, Integer>> playerItemMap = new HashMap<>();

    @Override
    public void onEnable() {
        connectDatabase();
        if (connection == null) {
            getLogger().severe("Не удалось подключиться к базе данных! Плагин выключается.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents((Listener) this, this);
    }

    @Override
    public void onDisable() {
        disconnectDatabase();
    }

    private void connectDatabase() {
        try {
            connection = DriverManager.getConnection(
                    "jdbc:mysql://26.62.236.14:3306/cursach", "root", "root"
            );
            getLogger().info("Подключено к базе данных!");
        } catch (SQLException e) {
            e.printStackTrace();
            connection = null;
        }
    }

    private void disconnectDatabase() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void openShop(Player player) {
        if (connection == null) {
            player.sendMessage("§cОшибка подключения к базе данных. Попробуйте позже!");
            getLogger().warning("Попытка открыть магазин, но соединение с базой данных не установлено.");
            return;
        }
        try {
            PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM orders WHERE game_name = ? AND delivered = 0"
            );
            ps.setString(1, player.getName());
            ResultSet rs = ps.executeQuery();

            Inventory inv = Bukkit.createInventory(null, 27, "Ваши покупки");

            Map<Integer, Integer> itemIdMap = new HashMap<>();
            int slot = 0;

            while (rs.next() && slot < inv.getSize()) {
                String category = rs.getString("category");
                String nameTag = rs.getString("nameTag");
                int quantity = rs.getInt("quantity");
                int id = rs.getInt("id");

                Material mat = Material.CHEST; // дефолтная иконка

                if (category.equals("Предметы")) {
                    try {
                        mat = Material.valueOf(nameTag.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        mat = Material.BARRIER; // если предмет неизвестен
                    }
                } else if (category.equals("Привилегия")) {
                    mat = Material.NETHER_STAR;
                }

                ItemStack item = new ItemStack(mat, Math.max(1, Math.min(quantity, mat.getMaxStackSize())));
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName("§a" + nameTag);
                item.setItemMeta(meta);

                inv.setItem(slot, item);
                itemIdMap.put(slot, id);
                slot++;
            }

            rs.close();
            ps.close();

            playerItemMap.put(player, itemIdMap);

            Bukkit.getScheduler().runTask(this, () -> player.openInventory(inv));

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (!event.getView().getTitle().equals("Ваши покупки")) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();
        Map<Integer, Integer> itemIdMap = playerItemMap.get(player);
        if (itemIdMap == null || !itemIdMap.containsKey(slot)) return;

        int orderId = itemIdMap.get(slot);

        // Сначала выдаём товар, потом обновляем БД
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT * FROM orders WHERE id = ?"
                );
                ps.setInt(1, orderId);
                ResultSet rs = ps.executeQuery();

                if (rs.next()) {
                    String category = rs.getString("category");
                    String nameTag = rs.getString("nameTag");
                    int quantity = rs.getInt("quantity");

                    String command = null;

                    switch (category) {
                        case "Привилегия":
                            command = "lp user " + player.getName() + " parent add " + nameTag;
                            break;
                        case "Предметы":
                            command = "give " + player.getName() + " " + nameTag + " " + quantity;
                            break;
                    }

                    if (command != null) {
                        String finalCommand = command;
                        Bukkit.getScheduler().runTask(this, () ->
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand)
                        );

                        // Обновляем БД: товар выдан
                        PreparedStatement update = connection.prepareStatement(
                                "UPDATE orders SET delivered = 1 WHERE id = ?"
                        );
                        update.setInt(1, orderId);
                        update.executeUpdate();
                        update.close();

                        player.sendMessage("§aТовар успешно выдан!");
                        player.closeInventory();
                    }
                }

                rs.close();
                ps.close();

            } catch (SQLException e) {
                e.printStackTrace();
                player.sendMessage("§cПроизошла ошибка при выдаче товара!");
            }
        });
    }
}