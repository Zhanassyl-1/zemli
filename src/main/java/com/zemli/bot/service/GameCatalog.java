package com.zemli.bot.service;

import com.zemli.bot.model.Faction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GameCatalog {

    public record Cost(int wood, int stone, int food, int iron, int gold, int mana, int alcohol) {
        public Cost multiply(int n) {
            return new Cost(wood * n, stone * n, food * n, iron * n, gold * n, mana * n, alcohol * n);
        }
    }

    public record BuildingSpec(String key, String title, String emoji, int requiredCityLevel, Cost cost, int hours) {}
    public record UnitSpec(String key, String title, int tier, String requiredBuilding, Cost cost, int power) {}

    private final Map<String, BuildingSpec> buildings = new LinkedHashMap<>();
    private final Map<Faction, List<UnitSpec>> unitsByFaction = new LinkedHashMap<>();
    private final Map<String, Map<Integer, Cost>> buildingUpgradeCosts = new LinkedHashMap<>();

    public GameCatalog() {
        buildings.put("TOWN_HALL", new BuildingSpec("TOWN_HALL", "Ратуша", "🏠", 1, new Cost(200, 150, 0, 0, 50, 0, 0), 4));
        buildings.put("BARRACKS", new BuildingSpec("BARRACKS", "Казарма", "🛡️", 1, new Cost(80, 60, 0, 0, 0, 0, 0), 2));
        buildings.put("RANGE", new BuildingSpec("RANGE", "Стрельбище", "🏹", 1, new Cost(120, 100, 0, 0, 0, 0, 0), 3));
        buildings.put("STABLE", new BuildingSpec("STABLE", "Конюшня", "🐎", 1, new Cost(200, 150, 0, 30, 0, 0, 0), 5));
        buildings.put("FARM", new BuildingSpec("FARM", "Ферма", "🌾", 1, new Cost(60, 40, 0, 0, 0, 0, 0), 1));
        buildings.put("MINE", new BuildingSpec("MINE", "Шахта", "⛏️", 1, new Cost(70, 50, 0, 0, 0, 0, 0), 2));
        buildings.put("LUMBERMILL", new BuildingSpec("LUMBERMILL", "Лесопилка", "🪵", 1, new Cost(90, 60, 0, 0, 0, 0, 0), 2));
        buildings.put("TAVERN", new BuildingSpec("TAVERN", "Таверна", "🍺", 1, new Cost(100, 80, 0, 0, 20, 0, 0), 2));
        buildings.put("MARKET", new BuildingSpec("MARKET", "Рынок", "🏦", 2, new Cost(150, 120, 0, 0, 50, 0, 0), 4));
        buildings.put("PORT", new BuildingSpec("PORT", "Порт", "⚓", 3, new Cost(200, 150, 0, 50, 0, 0, 0), 6));
        buildings.put("TEMPLE", new BuildingSpec("TEMPLE", "Храм", "🛕", 4, new Cost(250, 200, 0, 0, 0, 30, 0), 8));

        putUpgradeCosts("TOWN_HALL", Map.of(
                2, new Cost(500, 400, 0, 0, 200, 0, 0),
                3, new Cost(1200, 1000, 0, 0, 500, 0, 0),
                4, new Cost(2500, 2000, 0, 500, 1000, 0, 0),
                5, new Cost(5000, 4000, 0, 1000, 2000, 200, 0),
                6, new Cost(10000, 8000, 0, 2000, 5000, 500, 0),
                7, new Cost(20000, 15000, 0, 5000, 10000, 1000, 0)
        ));
        putUpgradeCosts("BARRACKS", Map.of(
                2, new Cost(150, 100, 0, 50, 0, 0, 0),
                3, new Cost(400, 300, 0, 150, 100, 0, 0)
        ));
        putUpgradeCosts("RANGE", Map.of(
                2, new Cost(180, 120, 0, 60, 0, 0, 0),
                3, new Cost(450, 350, 0, 180, 120, 0, 0)
        ));
        putUpgradeCosts("STABLE", Map.of(
                2, new Cost(250, 200, 0, 80, 50, 0, 0),
                3, new Cost(600, 500, 0, 200, 150, 0, 0)
        ));
        putUpgradeCosts("MINE", Map.of(
                2, new Cost(200, 150, 0, 50, 0, 0, 0),
                3, new Cost(500, 400, 0, 150, 0, 0, 0)
        ));
        putUpgradeCosts("LUMBERMILL", Map.of(
                2, new Cost(200, 150, 0, 50, 0, 0, 0),
                3, new Cost(500, 400, 0, 150, 0, 0, 0)
        ));
        putUpgradeCosts("FARM", Map.of(
                2, new Cost(150, 100, 0, 0, 0, 0, 0),
                3, new Cost(400, 300, 0, 0, 50, 0, 0)
        ));
        putUpgradeCosts("TAVERN", Map.of(
                2, new Cost(200, 150, 0, 0, 50, 0, 0),
                3, new Cost(500, 400, 0, 0, 150, 0, 0)
        ));

        unitsByFaction.put(Faction.KNIGHTS, List.of(
                new UnitSpec("SWORDSMAN", "Мечник", 1, "BARRACKS", new Cost(0, 0, 20, 0, 10, 0, 0), 5),
                new UnitSpec("CROSSBOWMAN", "Арбалетчик", 2, "RANGE", new Cost(0, 0, 30, 10, 20, 0, 0), 12),
                new UnitSpec("PALADIN", "Паладин", 3, "STABLE", new Cost(0, 0, 50, 20, 40, 0, 0), 25)
        ));
        unitsByFaction.put(Faction.SAMURAI, List.of(
                new UnitSpec("ASHIGARU", "Асигару", 1, "BARRACKS", new Cost(0, 0, 15, 0, 8, 0, 0), 5),
                new UnitSpec("SAM_ARCHER", "Лучник", 2, "RANGE", new Cost(0, 0, 25, 8, 15, 0, 0), 12),
                new UnitSpec("SAMURAI", "Самурай", 3, "STABLE", new Cost(0, 0, 45, 15, 35, 0, 0), 25)
        ));
        unitsByFaction.put(Faction.VIKINGS, List.of(
                new UnitSpec("BERSERK", "Берсерк", 1, "BARRACKS", new Cost(0, 0, 20, 0, 12, 0, 0), 6),
                new UnitSpec("VIK_ARCHER", "Лучник", 2, "RANGE", new Cost(0, 0, 30, 10, 20, 0, 0), 13),
                new UnitSpec("JARL", "Ярл", 3, "STABLE", new Cost(0, 0, 55, 20, 45, 0, 0), 28)
        ));
        unitsByFaction.put(Faction.MONGOLS, List.of(
                new UnitSpec("MONGOL_ARCHER", "Лучник", 1, "BARRACKS", new Cost(0, 0, 18, 0, 10, 0, 0), 5),
                new UnitSpec("RIDER", "Всадник", 2, "RANGE", new Cost(0, 0, 35, 12, 25, 0, 0), 15),
                new UnitSpec("KHAN", "Хан", 3, "STABLE", new Cost(0, 0, 60, 25, 50, 0, 0), 30)
        ));
        unitsByFaction.put(Faction.DESERT_DWELLERS, List.of(
                new UnitSpec("SPEARMAN", "Копейщик", 1, "BARRACKS", new Cost(0, 0, 15, 0, 8, 0, 0), 5),
                new UnitSpec("DES_ARCHER", "Лучник", 2, "RANGE", new Cost(0, 0, 25, 8, 15, 0, 0), 11),
                new UnitSpec("MAMLUK", "Мамлюк", 3, "STABLE", new Cost(0, 0, 45, 18, 35, 0, 0), 24)
        ));
        unitsByFaction.put(Faction.AZTECS, List.of(
                new UnitSpec("WARRIOR", "Воин", 1, "BARRACKS", new Cost(0, 0, 18, 0, 10, 0, 0), 5),
                new UnitSpec("JAGUAR", "Ягуар", 2, "RANGE", new Cost(0, 0, 30, 0, 20, 10, 0), 14),
                new UnitSpec("PRIEST", "Жрец-воин", 3, "STABLE", new Cost(0, 0, 50, 0, 40, 20, 0), 27)
        ));
    }

    private void putUpgradeCosts(String key, Map<Integer, Cost> levels) {
        buildingUpgradeCosts.put(key, new LinkedHashMap<>(levels));
    }

    public Map<String, BuildingSpec> buildings() {
        return buildings;
    }

    public Cost upgradeCost(String buildingKey, int toLevel) {
        Map<Integer, Cost> levels = buildingUpgradeCosts.get(buildingKey);
        if (levels == null) {
            return null;
        }
        return levels.get(toLevel);
    }

    public int maxBuildingLevel(String buildingKey) {
        if ("TOWN_HALL".equals(buildingKey)) {
            return 7;
        }
        return 3;
    }

    public String townHallUnlocks(int toLevel) {
        return switch (toLevel) {
            case 2 -> "Рынок, Порт";
            case 3 -> "Храм";
            case 4 -> "Улучшенные войска";
            case 5 -> "Эпические события";
            case 6 -> "Дипломатия";
            case 7 -> "Мировое доминирование";
            default -> "Новые возможности";
        };
    }

    public List<UnitSpec> unitsForFaction(Faction faction) {
        return unitsByFaction.getOrDefault(faction, List.of());
    }

    public UnitSpec unitByKey(Faction faction, String key) {
        return unitsForFaction(faction).stream().filter(u -> u.key().equals(key)).findFirst().orElse(null);
    }

    public Integer unitPowerByKey(String key) {
        for (List<UnitSpec> list : unitsByFaction.values()) {
            for (UnitSpec u : list) {
                if (u.key().equals(key)) {
                    return u.power();
                }
            }
        }
        return null;
    }

    public String shortFactionLabel(Faction faction) {
        return switch (faction) {
            case KNIGHTS -> "⚔️ Рыцари — броня, сильны против лучников";
            case SAMURAI -> "🥷 Самураи — мастера ближнего боя";
            case VIKINGS -> "🪓 Викинги — ярость и море";
            case MONGOLS -> "🏹 Монголы — быстрые всадники степи";
            case DESERT_DWELLERS -> "🐪 Пустынники — выносливые и дешёвые";
            case AZTECS -> "🗿 Ацтеки — магия и манна";
        };
    }

    public String fullFactionDescription(Faction faction) {
        return switch (faction) {
            case KNIGHTS -> "⚔️ Рыцари\nЮниты: Мечник → Арбалетчик → Паладин\nБонус: броня +20% против лучников";
            case SAMURAI -> "🥷 Самураи\nЮниты: Асигару → Лучник → Самурай\nБонус: ближний бой +15%";
            case VIKINGS -> "🪓 Викинги\nЮниты: Берсерк → Лучник → Ярл\nБонус: море +25%, порт даёт двойных юнитов";
            case MONGOLS -> "🏹 Монголы\nЮниты: Лучник → Всадник → Хан\nБонус в степи: шанс победы ×1.1, поражения ×0.9";
            case DESERT_DWELLERS -> "🐪 Пустынники\nЮниты: Копейщик → Лучник → Мамлюк\nБонус в жаре/пустыне +15%";
            case AZTECS -> "🗿 Ацтеки\nЮниты: Воин → Ягуар → Жрец-воин\nБонус: манна +20%, джунгли +15%";
        };
    }

    public String itemDisplay(String key) {
        return switch (key) {
            case "BLUEPRINT_WOODEN_SHIELD" -> "📜 Чертёж деревянного щита";
            case "BLUEPRINT_SIMPLE_BOW" -> "📜 Чертёж простого лука";
            case "BLUEPRINT_LEATHER_VEST" -> "📜 Чертёж кожаного жилета";
            case "BLUEPRINT_PISTOL" -> "📘 Чертёж пистолета";
            case "BLUEPRINT_CROSSBOW" -> "📘 Чертёж арбалета";
            case "BLUEPRINT_CATAPULT" -> "📘 Чертёж катапульты";
            case "BLUEPRINT_CANNON" -> "📕 Чертёж пушки";
            case "BLUEPRINT_FLAMETHROWER" -> "📕 Чертёж огнемёта";
            case "BLUEPRINT_MITHRIL_ARMOR" -> "📕 Чертёж мифриловой брони";

            case "CRAFTED_WOODEN_SHIELD" -> "🛡️ Деревянный щит";
            case "CRAFTED_SIMPLE_BOW" -> "🏹 Простой лук";
            case "LEATHER_VEST_ARMOR" -> "🧥 Кожаный жилет";
            case "CRAFTED_PISTOL" -> "🔫 Пистолет";
            case "CRAFTED_CANNON" -> "💣 Пушка";
            case "CRAFTED_CROSSBOW" -> "🏹 Усиленный арбалет";
            case "CRAFTED_CATAPULT" -> "🏰 Катапульта";
            case "CRAFTED_FLAMETHROWER" -> "🔥 Огнемёт";
            case "MITHRIL_ARMOR" -> "🛡️ Мифриловая броня";

            case "LEATHER_ARMOR" -> "🟫 Кожаная броня";
            case "CHAINMAIL_ARMOR" -> "⚪ Кольчуга";
            case "IRON_ARMOR" -> "⚫ Железная броня";
            case "GOLD_ARMOR" -> "🟡 Золотая броня";
            case "DIAMOND_ARMOR" -> "💎 Алмазная броня";
            case "NETHERITE_ARMOR" -> "🌑 Незеритовая броня";
            default -> key;
        };
    }

    public String itemDescription(String key) {
        return switch (key) {
            case "CRAFTED_PISTOL" -> "+15% урон в следующем авто бою";
            case "CRAFTED_CANNON" -> "+20% защита в авто бою";
            case "CRAFTED_CROSSBOW" -> "+10% шанс победы в авто бою";
            case "CRAFTED_FLAMETHROWER" -> "+30% урон в следующем авто бою";
            case "CRAFTED_WOODEN_SHIELD" -> "+5% защита в бою";
            case "CRAFTED_SIMPLE_BOW" -> "+5% урон лучников";
            case "CRAFTED_CATAPULT" -> "+25% защита города";
            case "LEATHER_VEST_ARMOR" -> "+3% защита (пассивно когда надета)";
            case "LEATHER_ARMOR" -> "+5% защита (пассивно когда надета)";
            case "CHAINMAIL_ARMOR" -> "+10% защита (пассивно когда надета)";
            case "IRON_ARMOR" -> "+20% защита (пассивно когда надета)";
            case "GOLD_ARMOR" -> "+30% защита (пассивно когда надета)";
            case "DIAMOND_ARMOR" -> "+40% защита (пассивно когда надета)";
            case "NETHERITE_ARMOR" -> "+50% защита (пассивно когда надета)";
            case "MITHRIL_ARMOR" -> "+45% защита (пассивно когда надета)";
            default -> "Предмет без подробного описания";
        };
    }
}
