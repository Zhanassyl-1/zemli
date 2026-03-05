package com.zemli.bot.dao;

import com.zemli.bot.model.BattleRecord;
import com.zemli.bot.model.BuildingState;
import com.zemli.bot.model.Faction;
import com.zemli.bot.model.KeyValueAmount;
import com.zemli.bot.model.MarketListing;
import com.zemli.bot.model.PlayerRecord;
import com.zemli.bot.model.ResourcesRecord;
import com.zemli.bot.service.GameCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class GameDao {
    private static final Logger log = LoggerFactory.getLogger(GameDao.class);

    public record WinnerStat(long winnerId, int wins) {}
    public record ArmyLoss(String unitType, int lost) {}
    public record EpicBattle(long attackerId, long defenderId, long winnerId, int attackerPower, int defenderPower) {}
    public record FactionStats(String faction, int players, int wins) {}
    public record RecentBattle(long attackerId, long defenderId, long winnerId, long createdAt) {}
    public record AlliancePair(long id, String name, String leaderVillage, int membersCount, int totalPower) {}
    public record AllianceInfo(long id, String name, long leaderId, long createdAt) {}
    public record AllianceMemberInfo(long playerId, long telegramId, String villageName, boolean leader, long joinedAt) {}
    public record AllianceInviteInfo(long id, long allianceId, String allianceName, long inviterId, String inviterVillage, long invitedPlayerId, long createdAt) {}
    public record PlayerBattleStats(int wins, int losses) {}
    public record TradeOffer(long id, long sellerId, String sellerVillage, String giveResource, int giveAmount, String wantResource, int wantAmount, long createdAt, long expiresAt, String status) {}
    public record Build(long playerId, String buildingType, int level) {}
    public record Point(int x, int y) {}
    public record CapitalPoint(long playerId, int x, int y) {}
    public record MapBuilding(long ownerId, int x, int y, String type, long builtAt) {}
    public record KingdomState(long playerId, String race, int homeX, int homeY, int level, Instant createdAt) {}

    private final JdbcTemplate jdbcTemplate;

    public GameDao(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        try {
            migrateAllianceSchemaIfNeeded();
            migrateArmyPowerSchemaIfNeeded();
            migrateMapBuildingsSchemaIfNeeded();
            migrateResourcesSchemaIfNeeded();
            migrateKingdomSchemaIfNeeded();
        } catch (Exception e) {
            log.error("Ошибка в GameDao при инициализации: {}", e.getMessage(), e);
            throw new IllegalStateException("GameDao initialization failed", e);
        }
    }

    private void migrateAllianceSchemaIfNeeded() {
        // No-op for PostgreSQL setup; schema is managed by schema.sql.
    }

    private void migrateArmyPowerSchemaIfNeeded() {
        jdbcTemplate.execute("ALTER TABLE army ADD COLUMN IF NOT EXISTS unit_power INTEGER NOT NULL DEFAULT 0");
        jdbcTemplate.execute(
                """
                UPDATE army
                SET unit_power = CASE unit_type
                    WHEN 'KN_MILITIA' THEN 10
                    WHEN 'KN_CROSSBOW' THEN 20
                    WHEN 'KN_SWORDSMAN' THEN 35
                    WHEN 'KN_HEAVY_KNIGHT' THEN 60
                    WHEN 'KN_PALADIN' THEN 100
                    WHEN 'SM_ASHIGARU' THEN 12
                    WHEN 'SM_ARCHER' THEN 18
                    WHEN 'SM_KATANA' THEN 40
                    WHEN 'SM_NINJA' THEN 45
                    WHEN 'SM_KENDO' THEN 85
                    WHEN 'VK_THRALL' THEN 10
                    WHEN 'VK_AXE_THROWER' THEN 25
                    WHEN 'VK_BERSERK' THEN 50
                    WHEN 'VK_HIRDMANN' THEN 45
                    WHEN 'VK_JARL' THEN 90
                    WHEN 'MG_HERDER' THEN 12
                    WHEN 'MG_HORSE_ARCHER' THEN 28
                    WHEN 'MG_BAGATUR' THEN 40
                    WHEN 'MG_NOYON' THEN 55
                    WHEN 'MG_CHINGIZID' THEN 95
                    WHEN 'DS_DRIVER' THEN 15
                    WHEN 'DS_JANISSARY' THEN 25
                    WHEN 'DS_MAMLUK' THEN 45
                    WHEN 'DS_ASSASSIN' THEN 50
                    WHEN 'DS_SULTAN' THEN 90
                    WHEN 'AZ_MASEUALLI' THEN 12
                    WHEN 'AZ_WAR_ARCHER' THEN 20
                    WHEN 'AZ_JAGUAR' THEN 40
                    WHEN 'AZ_EAGLE' THEN 45
                    WHEN 'AZ_PRIEST' THEN 70
                    ELSE unit_power
                END
                WHERE unit_power = 0
                """
        );
    }

    private void migrateMapBuildingsSchemaIfNeeded() {
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS map_buildings (
                    id BIGSERIAL PRIMARY KEY,
                    owner_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    building_type TEXT NOT NULL,
                    built_at BIGINT NOT NULL,
                    UNIQUE(owner_id, x, y)
                )
                """
        );
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_map_buildings_area ON map_buildings(x, y)");
    }

    private void migrateResourcesSchemaIfNeeded() {
        jdbcTemplate.execute("ALTER TABLE resources ADD COLUMN IF NOT EXISTS last_updated TIMESTAMP NOT NULL DEFAULT NOW()");
        jdbcTemplate.update("UPDATE resources SET last_updated = NOW() WHERE last_updated IS NULL");
    }

    private void migrateKingdomSchemaIfNeeded() {
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS kingdom (
                    player_id BIGINT PRIMARY KEY REFERENCES players(id) ON DELETE CASCADE,
                    race VARCHAR(50) NOT NULL,
                    home_x INTEGER NOT NULL DEFAULT 0,
                    home_y INTEGER NOT NULL DEFAULT 0,
                    level INTEGER NOT NULL DEFAULT 1,
                    created_at TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """
        );
    }

    public Optional<PlayerRecord> findPlayerByTelegramId(long telegramId) {
        List<PlayerRecord> players = jdbcTemplate.query(
                """
                SELECT id, telegram_id, village_name, faction, city_level,
                       builders_count, has_cannon, has_armor, has_crossbow, equipped_armor, created_at
                FROM players
                WHERE telegram_id = ?
                """,
                playerMapper(),
                telegramId
        );
        return players.stream().findFirst();
    }

    public Optional<PlayerRecord> findPlayerById(long playerId) {
        List<PlayerRecord> players = jdbcTemplate.query(
                """
                SELECT id, telegram_id, village_name, faction, city_level,
                       builders_count, has_cannon, has_armor, has_crossbow, equipped_armor, created_at
                FROM players
                WHERE id = ?
                """,
                playerMapper(),
                playerId
        );
        return players.stream().findFirst();
    }

    @Transactional
    public PlayerRecord createPlayer(long telegramId, String villageName, Faction faction) {
        long now = Instant.now().toEpochMilli();

        jdbcTemplate.update(
                """
                INSERT INTO players(telegram_id, village_name, faction, city_level, builders_count, has_cannon, has_armor, has_crossbow, equipped_armor, created_at)
                VALUES (?, ?, ?, 1, 1, 0, 0, 0, NULL, ?)
                """,
                telegramId,
                villageName,
                faction.name(),
                now
        );

        PlayerRecord created = findPlayerByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalStateException("Failed to load created player"));

        jdbcTemplate.update(
                """
                INSERT INTO resources(player_id, wood, stone, food, iron, gold, mana, alcohol)
                VALUES (?, 15000, 12000, 18000, 8000, 5000, 2000, 1000)
                """,
                created.id()
        );

        jdbcTemplate.update(
                """
                INSERT INTO buildings(player_id, building_type, level)
                VALUES (?, 'TOWN_HALL', 1)
                """,
                created.id()
        );

        upsertKingdom(created.id(), faction.name().toLowerCase(), 0, 0, 1);

        appendDailyLog("REGISTRATION", "🏡 " + villageName + " выбрал фракцию " + faction.getTitle());
        return created;
    }

    public ResourcesRecord loadResources(long playerId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT wood, stone, food, iron, gold, mana, alcohol
                FROM resources
                WHERE player_id = ?
                """,
                (rs, rowNum) -> new ResourcesRecord(
                        rs.getInt("wood"),
                        rs.getInt("stone"),
                        rs.getInt("food"),
                        rs.getInt("iron"),
                        rs.getInt("gold"),
                        rs.getInt("mana"),
                        rs.getInt("alcohol")
                ),
                playerId
        );
    }

    public List<KeyValueAmount> loadArmy(long playerId) {
        return jdbcTemplate.query(
                """
                SELECT unit_type, quantity
                FROM army
                WHERE player_id = ?
                ORDER BY unit_type
                """,
                (rs, rowNum) -> new KeyValueAmount(rs.getString("unit_type"), rs.getInt("quantity")),
                playerId
        );
    }

    public List<BuildingState> loadBuildingStates(long playerId) {
        return jdbcTemplate.query(
                """
                SELECT building_type, level
                FROM buildings
                WHERE player_id = ?
                ORDER BY building_type
                """,
                (rs, rowNum) -> new BuildingState(
                        rs.getString("building_type"),
                        rs.getInt("level")
                ),
                playerId
        );
    }

    public Map<String, BuildingState> loadBuildingMap(long playerId) {
        return loadBuildingStates(playerId).stream().collect(Collectors.toMap(BuildingState::buildingType, it -> it));
    }

    public Optional<BuildingState> loadBuilding(long playerId, String buildingType) {
        return loadBuildingStates(playerId).stream().filter(b -> b.buildingType().equals(buildingType)).findFirst();
    }

    public List<KeyValueAmount> loadInventory(long playerId) {
        return jdbcTemplate.query(
                """
                SELECT item_type, quantity
                FROM inventory
                WHERE player_id = ?
                ORDER BY item_type
                """,
                (rs, rowNum) -> new KeyValueAmount(rs.getString("item_type"), rs.getInt("quantity")),
                playerId
        );
    }

    public int calculateTotalArmyPower(long playerId, GameCatalog catalog, Faction faction) {
        return calculateTotalArmyPower(playerId, catalog);
    }

    public int calculateTotalArmyPower(long playerId, GameCatalog catalog) {
        Integer power = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity * unit_power), 0) FROM army WHERE player_id = ?",
                Integer.class,
                playerId
        );
        return power == null ? 0 : power;
    }

    public int rankByCityLevel(long playerId) {
        PlayerRecord player = findPlayerById(playerId).orElseThrow();
        Integer rank = jdbcTemplate.queryForObject(
                """
                SELECT 1 + COUNT(*)
                FROM players
                WHERE city_level > ?
                """,
                Integer.class,
                player.cityLevel()
        );
        return rank == null ? 1 : rank;
    }

    public List<PlayerRecord> allPlayers() {
        return jdbcTemplate.query(
                """
                SELECT id, telegram_id, village_name, faction, city_level,
                       builders_count, has_cannon, has_armor, has_crossbow, equipped_armor, created_at
                FROM players
                """,
                playerMapper()
        );
    }

    public List<PlayerRecord> attackTargets(long attackerId) {
        return jdbcTemplate.query(
                """
                SELECT id, telegram_id, village_name, faction, city_level,
                       builders_count, has_cannon, has_armor, has_crossbow, equipped_armor, created_at
                FROM players
                WHERE id <> ?
                ORDER BY city_level DESC, created_at ASC
                """,
                playerMapper(),
                attackerId
        );
    }

    public List<PlayerRecord> topPlayersByCityLevel(int limit) {
        return getTopPlayers(limit);
    }

    public List<PlayerRecord> getTopPlayers(int limit) {
        return jdbcTemplate.query(
                """
                SELECT id, telegram_id, village_name, faction, city_level,
                       builders_count, has_cannon, has_armor, has_crossbow, equipped_armor, created_at
                FROM players
                ORDER BY (
                    SELECT COALESCE(SUM(a.quantity * a.unit_power), 0)
                    FROM army a
                    WHERE a.player_id = players.id
                ) DESC,
                city_level DESC,
                created_at ASC
                LIMIT ?
                """,
                playerMapper(),
                limit
        );
    }

    public List<String> loadDailyEventDescriptions(Instant since) {
        return jdbcTemplate.query(
                """
                SELECT description
                FROM daily_log
                WHERE created_at >= ?
                ORDER BY created_at DESC
                LIMIT 20
                """,
                (rs, rowNum) -> rs.getString("description"),
                since.toEpochMilli()
        );
    }

    public void appendDailyLog(String eventType, String description) {
        jdbcTemplate.update(
                """
                INSERT INTO daily_log(event_type, description, created_at)
                VALUES (?, ?, ?)
                """,
                eventType,
                description,
                Instant.now().toEpochMilli()
        );
    }

    @Transactional
    public boolean spendResources(long playerId, GameCatalog.Cost cost) {
        int updated = jdbcTemplate.update(
                """
                UPDATE resources
                SET wood = wood - ?, stone = stone - ?, food = food - ?, iron = iron - ?,
                    gold = gold - ?, mana = mana - ?, alcohol = alcohol - ?
                WHERE player_id = ?
                  AND wood >= ? AND stone >= ? AND food >= ? AND iron >= ?
                  AND gold >= ? AND mana >= ? AND alcohol >= ?
                """,
                cost.wood(), cost.stone(), cost.food(), cost.iron(), cost.gold(), cost.mana(), cost.alcohol(),
                playerId,
                cost.wood(), cost.stone(), cost.food(), cost.iron(), cost.gold(), cost.mana(), cost.alcohol()
        );
        return updated > 0;
    }

    public void addResources(long playerId, GameCatalog.Cost cost) {
        jdbcTemplate.update(
                """
                UPDATE resources
                SET wood = wood + ?, stone = stone + ?, food = food + ?, iron = iron + ?,
                    gold = gold + ?, mana = mana + ?, alcohol = alcohol + ?
                WHERE player_id = ?
                """,
                cost.wood(), cost.stone(), cost.food(), cost.iron(), cost.gold(), cost.mana(), cost.alcohol(), playerId
        );
    }

    public boolean spendSingleResource(long playerId, String resourceKey, int amount) {
        if (amount <= 0) {
            return true;
        }
        String column = resourceColumn(resourceKey);
        int updated = jdbcTemplate.update(
                "UPDATE resources SET " + column + " = " + column + " - ? WHERE player_id = ? AND " + column + " >= ?",
                amount, playerId, amount
        );
        return updated > 0;
    }

    public void addSingleResource(long playerId, String resourceKey, int amount) {
        if (amount <= 0) {
            return;
        }
        String column = resourceColumn(resourceKey);
        jdbcTemplate.update(
                "UPDATE resources SET " + column + " = " + column + " + ? WHERE player_id = ?",
                amount, playerId
        );
    }

    public void setResourcesExact(long playerId, int wood, int stone, int food, int iron, int gold, int mana, int alcohol) {
        jdbcTemplate.update(
                """
                UPDATE resources
                SET wood = ?, stone = ?, food = ?, iron = ?, gold = ?, mana = ?, alcohol = ?
                WHERE player_id = ?
                """,
                wood, stone, food, iron, gold, mana, alcohol, playerId
        );
    }

    public void upsertArmy(String unitType, int unitPower, long playerId, int qty) {
        int updated = jdbcTemplate.update(
                "UPDATE army SET quantity = quantity + ?, unit_power = ? WHERE player_id = ? AND unit_type = ?",
                qty, unitPower, playerId, unitType
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO army(player_id, unit_type, quantity, unit_power) VALUES (?, ?, ?, ?)",
                    playerId, unitType, qty, unitPower
            );
        }
    }

    public void reduceArmyPercent(long playerId, int percent) {
        jdbcTemplate.update(
                "UPDATE army SET quantity = CASE WHEN quantity <= 0 THEN 0 ELSE CAST(quantity * ? / 100 AS INTEGER) END WHERE player_id = ?",
                (100 - percent), playerId
        );
    }

    public List<ArmyLoss> applyArmyLossPercent(long playerId, int percent) {
        if (percent <= 0) {
            return List.of();
        }
        List<ArmyLoss> losses = new ArrayList<>();
        for (KeyValueAmount unit : loadArmy(playerId)) {
            int qty = Math.max(0, unit.quantity());
            if (qty == 0) {
                continue;
            }
            int lost = Math.min(qty, (int) Math.ceil((qty * percent) / 100.0));
            if (lost <= 0) {
                continue;
            }
            jdbcTemplate.update(
                    "UPDATE army SET quantity = quantity - ? WHERE player_id = ? AND unit_type = ?",
                    lost, playerId, unit.type()
            );
            losses.add(new ArmyLoss(unit.type(), lost));
        }
        return losses;
    }

    public List<ArmyLoss> applyUnitLosses(long playerId, Map<String, Integer> lossesByUnit) {
        if (lossesByUnit == null || lossesByUnit.isEmpty()) {
            return Collections.emptyList();
        }
        List<ArmyLoss> applied = new ArrayList<>();
        for (Map.Entry<String, Integer> e : lossesByUnit.entrySet()) {
            int loss = Math.max(0, e.getValue());
            if (loss <= 0) {
                continue;
            }
            Integer current = jdbcTemplate.queryForObject(
                    "SELECT quantity FROM army WHERE player_id = ? AND unit_type = ?",
                    Integer.class,
                    playerId, e.getKey()
            );
            int have = current == null ? 0 : Math.max(0, current);
            int realLoss = Math.min(have, loss);
            if (realLoss <= 0) {
                continue;
            }
            jdbcTemplate.update(
                    "UPDATE army SET quantity = quantity - ? WHERE player_id = ? AND unit_type = ?",
                    realLoss, playerId, e.getKey()
            );
            applied.add(new ArmyLoss(e.getKey(), realLoss));
        }
        return applied;
    }

    public boolean hasInventoryItem(long playerId, String itemType) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT quantity FROM inventory WHERE player_id = ? AND item_type = ?",
                (rs, n) -> rs.getInt("quantity"),
                playerId, itemType
        );
        return !rows.isEmpty() && rows.get(0) > 0;
    }

    public int inventoryQuantity(long playerId, String itemType) {
        Integer q = jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory WHERE player_id = ? AND item_type = ?",
                Integer.class,
                playerId, itemType
        );
        return q == null ? 0 : q;
    }

    public void addInventoryItem(long playerId, String itemType, int qty) {
        int updated = jdbcTemplate.update(
                "UPDATE inventory SET quantity = quantity + ? WHERE player_id = ? AND item_type = ?",
                qty, playerId, itemType
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO inventory(player_id, item_type, quantity) VALUES (?, ?, ?)",
                    playerId, itemType, qty
            );
        }
    }

    public boolean removeInventoryItem(long playerId, String itemType, int qty) {
        int updated = jdbcTemplate.update(
                "UPDATE inventory SET quantity = quantity - ? WHERE player_id = ? AND item_type = ? AND quantity >= ?",
                qty, playerId, itemType, qty
        );
        return updated > 0;
    }

    public void setEquipment(long playerId, String equipmentKey, boolean value) {
        String column = switch (equipmentKey) {
            case "cannon" -> "has_cannon";
            case "armor" -> "has_armor";
            case "crossbow" -> "has_crossbow";
            default -> throw new IllegalArgumentException("unknown equipment");
        };
        jdbcTemplate.update("UPDATE players SET " + column + " = ? WHERE id = ?", value ? 1 : 0, playerId);
    }

    public void setEquippedArmor(long playerId, String armorType) {
        jdbcTemplate.update("UPDATE players SET equipped_armor = ? WHERE id = ?", armorType, playerId);
    }

    public void clearPlayerState(long playerId, String stateKey) {
        jdbcTemplate.update("DELETE FROM player_state WHERE player_id = ? AND state_key = ?", playerId, stateKey);
    }

    public void setPlayerState(long playerId, String stateKey, long stateValue) {
        int updated = jdbcTemplate.update(
                "UPDATE player_state SET state_value = ?, updated_at = ? WHERE player_id = ? AND state_key = ?",
                stateValue, Instant.now().toEpochMilli(), playerId, stateKey
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO player_state(player_id, state_key, state_value, updated_at) VALUES (?, ?, ?, ?)",
                    playerId, stateKey, stateValue, Instant.now().toEpochMilli()
            );
        }
    }

    public Long getPlayerState(long playerId, String stateKey) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT state_value FROM player_state WHERE player_id = ? AND state_key = ?",
                (rs, n) -> rs.getLong("state_value"),
                playerId, stateKey
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Optional<Point> loadCapital(long playerId) {
        Long x = getPlayerState(playerId, "CAPITAL_X");
        Long y = getPlayerState(playerId, "CAPITAL_Y");
        if (x == null || y == null) {
            return Optional.empty();
        }
        return Optional.of(new Point((int) x.longValue(), (int) y.longValue()));
    }

    public void saveCapital(long playerId, int x, int y) {
        setPlayerState(playerId, "CAPITAL_X", x);
        setPlayerState(playerId, "CAPITAL_Y", y);
        updateKingdomHome(playerId, x, y);
    }

    public void saveMapBuilding(long ownerId, int x, int y, String type) {
        long now = Instant.now().toEpochMilli();
        jdbcTemplate.update(
                """
                INSERT INTO map_buildings(owner_id, x, y, building_type, built_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (owner_id, x, y)
                DO UPDATE SET building_type = EXCLUDED.building_type, built_at = EXCLUDED.built_at
                """,
                ownerId, x, y, type, now
        );
    }

    public List<MapBuilding> loadMapBuildingsInArea(int minX, int minY, int maxX, int maxY) {
        return jdbcTemplate.query(
                """
                SELECT owner_id, x, y, building_type, built_at
                FROM map_buildings
                WHERE x BETWEEN ? AND ?
                  AND y BETWEEN ? AND ?
                ORDER BY built_at DESC
                LIMIT 500
                """,
                (rs, rowNum) -> new MapBuilding(
                        rs.getLong("owner_id"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getString("building_type"),
                        rs.getLong("built_at")
                ),
                minX, maxX, minY, maxY
        );
    }

    public List<MapBuilding> loadMapBuildingsByOwner(long ownerId) {
        return jdbcTemplate.query(
                """
                SELECT owner_id, x, y, building_type, built_at
                FROM map_buildings
                WHERE owner_id = ?
                ORDER BY built_at DESC
                LIMIT 2000
                """,
                (rs, rowNum) -> new MapBuilding(
                        rs.getLong("owner_id"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getString("building_type"),
                        rs.getLong("built_at")
                ),
                ownerId
        );
    }

    public Optional<KingdomState> loadKingdom(long playerId) {
        List<KingdomState> rows = jdbcTemplate.query(
                """
                SELECT player_id, race, home_x, home_y, level, created_at
                FROM kingdom
                WHERE player_id = ?
                """,
                (rs, rowNum) -> new KingdomState(
                        rs.getLong("player_id"),
                        rs.getString("race"),
                        rs.getInt("home_x"),
                        rs.getInt("home_y"),
                        rs.getInt("level"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                playerId
        );
        return rows.stream().findFirst();
    }

    public void upsertKingdom(long playerId, String race, int homeX, int homeY, int level) {
        jdbcTemplate.update(
                """
                INSERT INTO kingdom(player_id, race, home_x, home_y, level)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (player_id)
                DO UPDATE SET race = EXCLUDED.race, home_x = EXCLUDED.home_x, home_y = EXCLUDED.home_y, level = EXCLUDED.level
                """,
                playerId, race, homeX, homeY, level
        );
    }

    public void updateKingdomHome(long playerId, int homeX, int homeY) {
        jdbcTemplate.update(
                """
                INSERT INTO kingdom(player_id, race, home_x, home_y, level)
                VALUES (?, COALESCE((SELECT LOWER(faction) FROM players WHERE id = ?), 'knights'), ?, ?, 1)
                ON CONFLICT (player_id)
                DO UPDATE SET home_x = EXCLUDED.home_x, home_y = EXCLUDED.home_y
                """,
                playerId, playerId, homeX, homeY
        );
    }

    public int applyMapBuildingIncomeTick() {
        Instant now = Instant.now();
        int affected = 0;
        for (PlayerRecord player : allPlayers()) {
            long playerId = player.id();

            Timestamp lastUpdatedTs = jdbcTemplate.queryForObject(
                    "SELECT last_updated FROM resources WHERE player_id = ?",
                    Timestamp.class,
                    playerId
            );
            if (lastUpdatedTs == null) {
                continue;
            }

            Instant lastUpdated = lastUpdatedTs.toInstant();
            long elapsedSeconds = Math.max(0, Duration.between(lastUpdated, now).getSeconds());
            if (elapsedSeconds <= 0) {
                continue;
            }

            Map<String, Integer> countsByType = new HashMap<>();
            jdbcTemplate.query(
                    """
                    SELECT building_type, COUNT(*) AS cnt
                    FROM map_buildings
                    WHERE owner_id = ?
                    GROUP BY building_type
                    """,
                    (rs, rowNum) -> {
                        countsByType.put(normalizeMapBuildingType(rs.getString("building_type")), rs.getInt("cnt"));
                        return null;
                    },
                    playerId
            );

            int lumberCount = countsByType.getOrDefault("lumber", 0);
            int mineCount = countsByType.getOrDefault("mine", 0);
            int ironMineCount = countsByType.getOrDefault("iron_mine", 0);
            int goldMineCount = countsByType.getOrDefault("gold_mine", 0);
            int farmCount = countsByType.getOrDefault("farm", 0);

            int woodGain = calcHourlyGain(elapsedSeconds, lumberCount, 5);
            int stoneGain = calcHourlyGain(elapsedSeconds, mineCount, 3);
            int ironGain = calcHourlyGain(elapsedSeconds, ironMineCount, 2);
            int goldGain = calcHourlyGain(elapsedSeconds, goldMineCount, 1);
            int foodGain = calcHourlyGain(elapsedSeconds, farmCount, 10);

            jdbcTemplate.update(
                    """
                    UPDATE resources
                    SET wood = wood + ?,
                        stone = stone + ?,
                        iron = iron + ?,
                        gold = gold + ?,
                        food = food + ?,
                        last_updated = NOW()
                    WHERE player_id = ?
                    """,
                    woodGain, stoneGain, ironGain, goldGain, foodGain, playerId
            );

            if (woodGain > 0 || stoneGain > 0 || ironGain > 0 || goldGain > 0 || foodGain > 0) {
                affected++;
            }
        }
        return affected;
    }

    private int calcHourlyGain(long elapsedSeconds, int buildingCount, int perHourPerBuilding) {
        if (buildingCount <= 0 || perHourPerBuilding <= 0 || elapsedSeconds <= 0) {
            return 0;
        }
        double amount = (elapsedSeconds / 3600.0) * perHourPerBuilding * buildingCount;
        return (int) Math.floor(amount);
    }

    private String normalizeMapBuildingType(String buildingType) {
        if (buildingType == null) {
            return "";
        }
        String value = buildingType.trim().toLowerCase();
        if (value.equals("gold")) {
            return "gold_mine";
        }
        if (value.equals("iron") || value.equals("ironmine")) {
            return "iron_mine";
        }
        return value;
    }

    public List<Point> loadAllCapitals() {
        return jdbcTemplate.query(
                """
                SELECT player_id,
                       MAX(CASE WHEN state_key = 'CAPITAL_X' THEN state_value END) AS capital_x,
                       MAX(CASE WHEN state_key = 'CAPITAL_Y' THEN state_value END) AS capital_y
                FROM player_state
                WHERE state_key IN ('CAPITAL_X', 'CAPITAL_Y')
                GROUP BY player_id
                HAVING MAX(CASE WHEN state_key = 'CAPITAL_X' THEN state_value END) IS NOT NULL
                   AND MAX(CASE WHEN state_key = 'CAPITAL_Y' THEN state_value END) IS NOT NULL
                """,
                (rs, rowNum) -> new Point(rs.getInt("capital_x"), rs.getInt("capital_y"))
        );
    }

    public List<CapitalPoint> loadAllCapitalsWithOwners() {
        return jdbcTemplate.query(
                """
                SELECT player_id,
                       MAX(CASE WHEN state_key = 'CAPITAL_X' THEN state_value END) AS capital_x,
                       MAX(CASE WHEN state_key = 'CAPITAL_Y' THEN state_value END) AS capital_y
                FROM player_state
                WHERE state_key IN ('CAPITAL_X', 'CAPITAL_Y')
                GROUP BY player_id
                HAVING MAX(CASE WHEN state_key = 'CAPITAL_X' THEN state_value END) IS NOT NULL
                   AND MAX(CASE WHEN state_key = 'CAPITAL_Y' THEN state_value END) IS NOT NULL
                """,
                (rs, rowNum) -> new CapitalPoint(
                        rs.getLong("player_id"),
                        rs.getInt("capital_x"),
                        rs.getInt("capital_y")
                )
        );
    }

    public int loadMorale(long playerId) {
        Integer morale = jdbcTemplate.queryForObject(
                "SELECT morale FROM players WHERE id = ?",
                Integer.class,
                playerId
        );
        return morale == null ? 100 : Math.max(0, Math.min(100, morale));
    }

    public void setMorale(long playerId, int morale) {
        jdbcTemplate.update(
                "UPDATE players SET morale = ? WHERE id = ?",
                Math.max(0, Math.min(100, morale)),
                playerId
        );
    }

    public int changeMorale(long playerId, int delta) {
        int next = Math.max(0, Math.min(100, loadMorale(playerId) + delta));
        setMorale(playerId, next);
        return next;
    }

    public void touchActivity(long playerId) {
        setPlayerState(playerId, "LAST_ACTIVE_AT", Instant.now().toEpochMilli());
    }

    public boolean isPassiveBuildingActive(long playerId, String buildingKey) {
        Long state = getPlayerState(playerId, "PASSIVE_ACTIVE_" + buildingKey);
        return state == null || state == 1L;
    }

    public void setPassiveBuildingActive(long playerId, String buildingKey, boolean active) {
        setPlayerState(playerId, "PASSIVE_ACTIVE_" + buildingKey, active ? 1L : 0L);
    }

    public int applyInactivityMoraleDecay() {
        long now = Instant.now().toEpochMilli();
        int affected = 0;
        for (PlayerRecord player : allPlayers()) {
            Long lastActive = getPlayerState(player.id(), "LAST_ACTIVE_AT");
            Long lastDecay = getPlayerState(player.id(), "MORALE_LAST_DECAY_AT");
            long activityMark = lastActive == null ? player.createdAt() : lastActive;
            boolean inactiveForDay = now - activityMark >= 24L * 60L * 60L * 1000L;
            boolean canDecayAgain = lastDecay == null || now - lastDecay >= 24L * 60L * 60L * 1000L;
            if (inactiveForDay && canDecayAgain) {
                changeMorale(player.id(), -5);
                setPlayerState(player.id(), "MORALE_LAST_DECAY_AT", now);
                affected++;
            }
        }
        return affected;
    }

    @Transactional
    public int applyPassiveIncomeTick() {
        return applyPassiveIncomeTick(1.0);
    }

    @Transactional
    public int applyPassiveIncomeTick(double multiplier) {
        int updated = 0;
        List<PlayerRecord> players = allPlayers();
        for (PlayerRecord player : players) {
            Map<String, BuildingState> levels = loadBuildingMap(player.id());
            int mineLvl = levels.getOrDefault("MINE", new BuildingState("MINE", 0)).level();
            int farmLvl = levels.getOrDefault("FARM", new BuildingState("FARM", 0)).level();
            int lumberLvl = levels.getOrDefault("LUMBERMILL", new BuildingState("LUMBERMILL", 0)).level();
            int tavernLvl = levels.getOrDefault("TAVERN", new BuildingState("TAVERN", 0)).level();
            int templeLvl = levels.getOrDefault("TEMPLE", new BuildingState("TEMPLE", 0)).level();
            int marketLvl = levels.getOrDefault("MARKET", new BuildingState("MARKET", 0)).level();
            int morale = loadMorale(player.id());
            boolean mineActive = isPassiveBuildingActive(player.id(), "MINE");
            boolean farmActive = isPassiveBuildingActive(player.id(), "FARM");
            boolean lumberActive = isPassiveBuildingActive(player.id(), "LUMBERMILL");

            double moraleMul = morale < 20 ? 0.70 : 1.0;
            int woodIncome = (int) Math.floor((5 + (lumberActive ? lumbermillBonus(lumberLvl) : 0)) * multiplier * moraleMul);
            int stoneIncome = (int) Math.floor((3 + (mineActive ? mineStoneBonus(mineLvl) : 0)) * multiplier * moraleMul);
            int foodIncome = (int) Math.floor((4 + (farmActive ? farmBonus(farmLvl) : 0)) * multiplier * moraleMul);
            int ironIncome = (int) Math.floor((2 + (mineActive ? mineIronBonus(mineLvl) : 0)) * multiplier * moraleMul);
            int goldIncome = (int) Math.floor((1 + marketBonus(marketLvl)) * multiplier * moraleMul);
            int manaIncome = (int) Math.floor(templeBonus(templeLvl) * multiplier * moraleMul);
            int alcoholIncome = (int) Math.floor(tavernBonus(tavernLvl) * multiplier * moraleMul);

            jdbcTemplate.update(
                    """
                    UPDATE resources
                    SET wood = wood + ?,
                        stone = stone + ?,
                        food = food + ?,
                        iron = iron + ?,
                        gold = gold + ?,
                        mana = mana + ?,
                        alcohol = alcohol + ?
                    WHERE player_id = ?
                    """,
                    woodIncome,
                    stoneIncome,
                    foodIncome,
                    ironIncome,
                    goldIncome,
                    manaIncome,
                    alcoholIncome,
                    player.id()
            );
            updated++;
        }
        return updated;
    }

    public List<MarketListing> findEndedAuctions() {
        long now = Instant.now().toEpochMilli();
        return jdbcTemplate.query(
                """
                SELECT id, seller_id, item_type, price, is_auction,
                       auction_ends_at, highest_bidder_id, highest_bid
                FROM market_listings
                WHERE is_auction = 1
                  AND auction_ends_at IS NOT NULL
                  AND auction_ends_at <= ?
                """,
                marketListingMapper(),
                now
        );
    }

    public List<MarketListing> findActiveAuctions() {
        long now = Instant.now().toEpochMilli();
        return jdbcTemplate.query(
                """
                SELECT id, seller_id, item_type, price, is_auction,
                       auction_ends_at, highest_bidder_id, highest_bid
                FROM market_listings
                WHERE is_auction = 1
                  AND auction_ends_at > ?
                ORDER BY auction_ends_at ASC
                LIMIT 20
                """,
                marketListingMapper(),
                now
        );
    }

    public Optional<MarketListing> findListing(long listingId) {
        List<MarketListing> rows = jdbcTemplate.query(
                """
                SELECT id, seller_id, item_type, price, is_auction,
                       auction_ends_at, highest_bidder_id, highest_bid
                FROM market_listings
                WHERE id = ?
                """,
                marketListingMapper(),
                listingId
        );
        return rows.stream().findFirst();
    }

    public long createAuction(long sellerId, String itemType, int startPrice, long endsAt) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO market_listings(seller_id, item_type, price, is_auction, auction_ends_at, highest_bidder_id, highest_bid)
                VALUES (?, ?, ?, 1, ?, NULL, NULL)
                RETURNING id
                """,
                Long.class,
                sellerId, itemType, startPrice, endsAt
        );
        if (id == null) {
            throw new IllegalStateException("Failed to create auction");
        }
        return id;
    }

    public long createDirectListing(long sellerId, long buyerId, String itemType, int price) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO market_listings(seller_id, item_type, price, is_auction, auction_ends_at, highest_bidder_id, highest_bid)
                VALUES (?, ?, ?, 0, NULL, ?, NULL)
                RETURNING id
                """,
                Long.class,
                sellerId, itemType, price, buyerId
        );
        if (id == null) {
            throw new IllegalStateException("Failed to create direct listing");
        }
        return id;
    }

    @Transactional
    public boolean placeBid(long bidderId, long listingId, int amount) {
        int ok = jdbcTemplate.update(
                """
                UPDATE market_listings
                SET highest_bidder_id = ?, highest_bid = ?
                WHERE id = ? AND is_auction = 1
                """,
                bidderId, amount, listingId
        );
        return ok > 0;
    }

    @Transactional
    public boolean acceptDirectDeal(long listingId, long buyerId) {
        Optional<MarketListing> row = findListing(listingId);
        if (row.isEmpty()) {
            return false;
        }
        MarketListing listing = row.get();
        if (listing.auction() || listing.highestBidderId() == null || listing.highestBidderId() != buyerId) {
            return false;
        }
        GameCatalog.Cost cost = new GameCatalog.Cost(0, 0, 0, 0, listing.price(), 0, 0);
        if (!spendResources(buyerId, cost)) {
            return false;
        }
        addResources(listing.sellerId(), cost);
        addInventoryItem(buyerId, listing.itemType(), 1);
        jdbcTemplate.update("DELETE FROM market_listings WHERE id = ?", listingId);
        return true;
    }

    public void declineDirectDeal(long listingId) {
        Optional<MarketListing> row = findListing(listingId);
        row.ifPresent(listing -> addInventoryItem(listing.sellerId(), listing.itemType(), 1));
        jdbcTemplate.update("DELETE FROM market_listings WHERE id = ? AND is_auction = 0", listingId);
    }

    @Transactional
    public void closeAuction(MarketListing listing) {
        if (listing.highestBidderId() != null && listing.highestBid() != null) {
            boolean debited = spendResources(listing.highestBidderId(), new GameCatalog.Cost(0, 0, 0, 0, listing.highestBid(), 0, 0));
            if (debited) {
                addResources(listing.sellerId(), new GameCatalog.Cost(0, 0, 0, 0, listing.highestBid(), 0, 0));
                addInventoryItem(listing.highestBidderId(), listing.itemType(), 1);
            } else {
                addInventoryItem(listing.sellerId(), listing.itemType(), 1);
            }
        } else {
            addInventoryItem(listing.sellerId(), listing.itemType(), 1);
        }

        jdbcTemplate.update("DELETE FROM market_listings WHERE id = ?", listing.id());
    }

    public int countActiveTradeOffers(long sellerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trade_offers WHERE seller_id = ? AND status = 'ACTIVE' AND expires_at > ?",
                Integer.class,
                sellerId,
                Instant.now().toEpochMilli()
        );
        return count == null ? 0 : count;
    }

    public List<TradeOffer> listActiveTradeOffers(int limit) {
        long now = Instant.now().toEpochMilli();
        return jdbcTemplate.query(
                """
                SELECT o.id, o.seller_id, p.village_name AS seller_village, o.give_resource, o.give_amount,
                       o.want_resource, o.want_amount, o.created_at, o.expires_at, o.status
                FROM trade_offers o
                JOIN players p ON p.id = o.seller_id
                WHERE o.status = 'ACTIVE' AND o.expires_at > ?
                ORDER BY o.created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new TradeOffer(
                        rs.getLong("id"),
                        rs.getLong("seller_id"),
                        rs.getString("seller_village"),
                        rs.getString("give_resource"),
                        rs.getInt("give_amount"),
                        rs.getString("want_resource"),
                        rs.getInt("want_amount"),
                        rs.getLong("created_at"),
                        rs.getLong("expires_at"),
                        rs.getString("status")
                ),
                now, limit
        );
    }

    public Optional<TradeOffer> findTradeOffer(long offerId) {
        List<TradeOffer> rows = jdbcTemplate.query(
                """
                SELECT o.id, o.seller_id, p.village_name AS seller_village, o.give_resource, o.give_amount,
                       o.want_resource, o.want_amount, o.created_at, o.expires_at, o.status
                FROM trade_offers o
                JOIN players p ON p.id = o.seller_id
                WHERE o.id = ?
                """,
                (rs, rowNum) -> new TradeOffer(
                        rs.getLong("id"),
                        rs.getLong("seller_id"),
                        rs.getString("seller_village"),
                        rs.getString("give_resource"),
                        rs.getInt("give_amount"),
                        rs.getString("want_resource"),
                        rs.getInt("want_amount"),
                        rs.getLong("created_at"),
                        rs.getLong("expires_at"),
                        rs.getString("status")
                ),
                offerId
        );
        return rows.stream().findFirst();
    }

    public long createTradeOffer(long sellerId, String giveResource, int giveAmount, String wantResource, int wantAmount, long expiresAt) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO trade_offers(seller_id, give_resource, give_amount, want_resource, want_amount, created_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                RETURNING id
                """,
                Long.class,
                sellerId, giveResource, giveAmount, wantResource, wantAmount, Instant.now().toEpochMilli(), expiresAt
        );
        if (id == null) {
            throw new IllegalStateException("Failed to create trade offer");
        }
        return id;
    }

    @Transactional
    public boolean acceptTradeOffer(long offerId, long buyerId) {
        Optional<TradeOffer> offerOpt = findTradeOffer(offerId);
        if (offerOpt.isEmpty()) {
            return false;
        }
        TradeOffer offer = offerOpt.get();
        long now = Instant.now().toEpochMilli();
        if (!"ACTIVE".equals(offer.status()) || offer.expiresAt() <= now || offer.sellerId() == buyerId) {
            return false;
        }

        if (!spendSingleResource(buyerId, offer.wantResource(), offer.wantAmount())) {
            return false;
        }
        addSingleResource(offer.sellerId(), offer.wantResource(), offer.wantAmount());
        addSingleResource(buyerId, offer.giveResource(), offer.giveAmount());
        jdbcTemplate.update("UPDATE trade_offers SET status = 'COMPLETED' WHERE id = ? AND status = 'ACTIVE'", offerId);
        return true;
    }

    @Transactional
    public int expireTradeOffersAndRefund() {
        long now = Instant.now().toEpochMilli();
        List<TradeOffer> expired = jdbcTemplate.query(
                """
                SELECT o.id, o.seller_id, p.village_name AS seller_village, o.give_resource, o.give_amount,
                       o.want_resource, o.want_amount, o.created_at, o.expires_at, o.status
                FROM trade_offers o
                JOIN players p ON p.id = o.seller_id
                WHERE o.status = 'ACTIVE' AND o.expires_at <= ?
                """,
                (rs, rowNum) -> new TradeOffer(
                        rs.getLong("id"),
                        rs.getLong("seller_id"),
                        rs.getString("seller_village"),
                        rs.getString("give_resource"),
                        rs.getInt("give_amount"),
                        rs.getString("want_resource"),
                        rs.getInt("want_amount"),
                        rs.getLong("created_at"),
                        rs.getLong("expires_at"),
                        rs.getString("status")
                ),
                now
        );

        for (TradeOffer offer : expired) {
            addSingleResource(offer.sellerId(), offer.giveResource(), offer.giveAmount());
            jdbcTemplate.update("UPDATE trade_offers SET status = 'EXPIRED' WHERE id = ?", offer.id());
        }
        return expired.size();
    }

    public List<PlayerRecord> playersForDirectTrade(long sellerId) {
        return jdbcTemplate.query(
                """
                SELECT id, telegram_id, village_name, faction, city_level,
                       builders_count, has_cannon, has_armor, has_crossbow, equipped_armor, created_at
                FROM players
                WHERE id <> ?
                ORDER BY city_level DESC, village_name ASC
                LIMIT 20
                """,
                playerMapper(),
                sellerId
        );
    }

    @Transactional
    public boolean stealThirtyPercentResources(long winnerId, long loserId) {
        ResourcesRecord r = loadResources(loserId);
        GameCatalog.Cost stolen = new GameCatalog.Cost(
                (int) Math.floor(r.wood() * 0.30),
                (int) Math.floor(r.stone() * 0.30),
                (int) Math.floor(r.food() * 0.30),
                (int) Math.floor(r.iron() * 0.30),
                (int) Math.floor(r.gold() * 0.30),
                (int) Math.floor(r.mana() * 0.30),
                (int) Math.floor(r.alcohol() * 0.30)
        );

        jdbcTemplate.update(
                """
                UPDATE resources
                SET wood = wood - ?, stone = stone - ?, food = food - ?, iron = iron - ?,
                    gold = gold - ?, mana = mana - ?, alcohol = alcohol - ?
                WHERE player_id = ?
                """,
                stolen.wood(), stolen.stone(), stolen.food(), stolen.iron(),
                stolen.gold(), stolen.mana(), stolen.alcohol(), loserId
        );

        addResources(winnerId, stolen);
        return true;
    }

    public void spendBattleRecovery(long playerId) {
        jdbcTemplate.update(
                """
                UPDATE resources
                SET food = CASE WHEN food >= 10 THEN food - 10 ELSE 0 END,
                    gold = CASE WHEN gold >= 5 THEN gold - 5 ELSE 0 END,
                    alcohol = CASE WHEN alcohol >= 3 THEN alcohol - 3 ELSE 0 END
                WHERE player_id = ?
                """,
                playerId
        );
    }

    public void logBattle(long attackerId, long defenderId, int attackerPower, int defenderPower, long winnerId, int stolenGold) {
        jdbcTemplate.update(
                """
                INSERT INTO battle_log(attacker_id, defender_id, attacker_power, defender_power, winner_id, resources_stolen, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                attackerId, defenderId, attackerPower, defenderPower, winnerId, stolenGold, Instant.now().toEpochMilli()
        );
    }

    public int battlesCountSince(long since) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM battle_log WHERE created_at >= ?",
                Integer.class,
                since
        );
        return count == null ? 0 : count;
    }

    public int rareFindsCountSince(long since) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_log WHERE event_type = 'RARE' AND created_at >= ?",
                Integer.class,
                since
        );
        return count == null ? 0 : count;
    }

    public Optional<WinnerStat> topWinnerSince(long since) {
        List<WinnerStat> rows = jdbcTemplate.query(
                """
                SELECT winner_id, COUNT(*) AS wins
                FROM battle_log
                WHERE created_at >= ?
                GROUP BY winner_id
                ORDER BY wins DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new WinnerStat(rs.getLong("winner_id"), rs.getInt("wins")),
                since
        );
        return rows.stream().findFirst();
    }

    public Optional<PlayerRecord> mostAttackedSince(long since) {
        List<PlayerRecord> rows = jdbcTemplate.query(
                """
                SELECT p.id, p.telegram_id, p.village_name, p.faction, p.city_level,
                       p.builders_count, p.has_cannon, p.has_armor, p.has_crossbow, p.equipped_armor, p.created_at
                FROM players p
                JOIN (
                    SELECT defender_id, COUNT(*) c
                    FROM battle_log
                    WHERE created_at >= ?
                    GROUP BY defender_id
                    ORDER BY c DESC
                    LIMIT 1
                ) t ON t.defender_id = p.id
                """,
                playerMapper(),
                since
        );
        return rows.stream().findFirst();
    }

    public int mostAttackedCountSince(long since) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(MAX(c), 0)
                FROM (
                    SELECT COUNT(*) c
                    FROM battle_log
                    WHERE created_at >= ?
                    GROUP BY defender_id
                )
                """,
                Integer.class,
                since
        );
        return count == null ? 0 : count;
    }

    public int builtCountSince(long since) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_log WHERE event_type = 'BUILDING_DONE' AND created_at >= ?",
                Integer.class,
                since
        );
        return count == null ? 0 : count;
    }

    public Optional<EpicBattle> epicBattleSince(long since) {
        List<EpicBattle> rows = jdbcTemplate.query(
                """
                SELECT attacker_id, defender_id, winner_id, attacker_power, defender_power
                FROM battle_log
                WHERE created_at >= ?
                ORDER BY (attacker_power + defender_power) DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new EpicBattle(
                        rs.getLong("attacker_id"),
                        rs.getLong("defender_id"),
                        rs.getLong("winner_id"),
                        rs.getInt("attacker_power"),
                        rs.getInt("defender_power")
                ),
                since
        );
        return rows.stream().findFirst();
    }

    public int activePlayersSince(long since) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(DISTINCT player_id)
                FROM (
                    SELECT attacker_id AS player_id, created_at FROM battle_log
                    UNION ALL
                    SELECT defender_id AS player_id, created_at FROM battle_log
                ) t
                WHERE created_at >= ?
                """,
                Integer.class,
                since
        );
        return count == null ? 0 : count;
    }

    public int totalPlayers() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM players", Integer.class);
        return count == null ? 0 : count;
    }

    public int totalBattles() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM battle_log", Integer.class);
        return count == null ? 0 : count;
    }

    public int totalBuilt() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM daily_log WHERE event_type = 'BUILDING_DONE'", Integer.class);
        return count == null ? 0 : count;
    }

    public int totalRareFinds() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM daily_log WHERE event_type = 'RARE'", Integer.class);
        return count == null ? 0 : count;
    }

    public long totalGoldInCirculation() {
        Long sum = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(gold), 0) FROM resources", Long.class);
        return sum == null ? 0L : sum;
    }

    public int totalAlliances() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM alliances", Integer.class);
        return count == null ? 0 : count;
    }

    public List<AlliancePair> listAlliances(int limit) {
        return jdbcTemplate.query(
                """
                SELECT a.id,
                       a.name,
                       leader.village_name AS leader_village,
                       COUNT(am.player_id) AS members_count
                FROM alliances a
                JOIN players leader ON leader.id = a.leader_id
                LEFT JOIN alliance_members am ON am.alliance_id = a.id
                GROUP BY a.id, a.name, leader.village_name
                ORDER BY a.created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new AlliancePair(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("leader_village"),
                        rs.getInt("members_count"),
                        0
                ),
                limit
        );
    }

    public Optional<AllianceInfo> findAllianceById(long allianceId) {
        List<AllianceInfo> rows = jdbcTemplate.query(
                "SELECT id, name, leader_id, created_at FROM alliances WHERE id = ?",
                (rs, rowNum) -> new AllianceInfo(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getLong("leader_id"),
                        rs.getLong("created_at")
                ),
                allianceId
        );
        return rows.stream().findFirst();
    }

    public Optional<AllianceInfo> findAllianceByPlayerId(long playerId) {
        List<AllianceInfo> rows = jdbcTemplate.query(
                """
                SELECT a.id, a.name, a.leader_id, a.created_at
                FROM alliances a
                JOIN alliance_members m ON m.alliance_id = a.id
                WHERE m.player_id = ?
                LIMIT 1
                """,
                (rs, rowNum) -> new AllianceInfo(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getLong("leader_id"),
                        rs.getLong("created_at")
                ),
                playerId
        );
        return rows.stream().findFirst();
    }

    @Transactional
    public Optional<AllianceInfo> createAlliance(String name, long leaderId) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO alliances(name, leader_id, created_at) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                name, leaderId, Instant.now().toEpochMilli()
        );
        if (id == null) {
            return Optional.empty();
        }
        jdbcTemplate.update(
                "INSERT INTO alliance_members(alliance_id, player_id, joined_at) VALUES (?, ?, ?)",
                id, leaderId, Instant.now().toEpochMilli()
        );
        return findAllianceById(id);
    }

    public List<AllianceMemberInfo> allianceMembers(long allianceId) {
        return jdbcTemplate.query(
                """
                SELECT p.id AS player_id, p.telegram_id, p.village_name, m.joined_at,
                       CASE WHEN a.leader_id = p.id THEN 1 ELSE 0 END AS is_leader
                FROM alliance_members m
                JOIN players p ON p.id = m.player_id
                JOIN alliances a ON a.id = m.alliance_id
                WHERE m.alliance_id = ?
                ORDER BY is_leader DESC, p.village_name ASC
                """,
                (rs, rowNum) -> new AllianceMemberInfo(
                        rs.getLong("player_id"),
                        rs.getLong("telegram_id"),
                        rs.getString("village_name"),
                        rs.getInt("is_leader") == 1,
                        rs.getLong("joined_at")
                ),
                allianceId
        );
    }

    public int allianceTotalPower(long allianceId, GameCatalog catalog) {
        int sum = 0;
        for (AllianceMemberInfo member : allianceMembers(allianceId)) {
            sum += calculateTotalArmyPower(member.playerId(), catalog);
        }
        return sum;
    }

    public boolean isAllianceLeader(long allianceId, long playerId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alliances WHERE id = ? AND leader_id = ?",
                Integer.class,
                allianceId, playerId
        );
        return cnt != null && cnt > 0;
    }

    public boolean addAllianceMember(long allianceId, long playerId) {
        int inserted = jdbcTemplate.update(
                "INSERT INTO alliance_members(alliance_id, player_id, joined_at) VALUES (?, ?, ?)",
                allianceId, playerId, Instant.now().toEpochMilli()
        );
        return inserted > 0;
    }

    public boolean removeAllianceMember(long allianceId, long playerId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM alliance_members WHERE alliance_id = ? AND player_id = ?",
                allianceId, playerId
        );
        Integer left = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alliance_members WHERE alliance_id = ?",
                Integer.class,
                allianceId
        );
        if (left != null && left == 0) {
            jdbcTemplate.update("DELETE FROM alliances WHERE id = ?", allianceId);
        }
        return deleted > 0;
    }

    public List<PlayerRecord> playersWithoutAlliance(long excludePlayerId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT p.id, p.telegram_id, p.village_name, p.faction, p.city_level,
                       p.builders_count, p.has_cannon, p.has_armor, p.has_crossbow, p.equipped_armor, p.created_at
                FROM players p
                LEFT JOIN alliance_members am ON am.player_id = p.id
                WHERE p.id <> ? AND am.player_id IS NULL
                ORDER BY p.city_level DESC, p.village_name ASC
                LIMIT ?
                """,
                playerMapper(),
                excludePlayerId, limit
        );
    }

    public long createAllianceInvite(long allianceId, long inviterId, long invitedPlayerId) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO alliance_invites(alliance_id, inviter_id, invited_player_id, created_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                allianceId, inviterId, invitedPlayerId, Instant.now().toEpochMilli()
        );
        return id == null ? 0L : id;
    }

    public Optional<AllianceInviteInfo> findAllianceInvite(long inviteId) {
        List<AllianceInviteInfo> rows = jdbcTemplate.query(
                """
                SELECT i.id, i.alliance_id, a.name AS alliance_name, i.inviter_id, inv.village_name AS inviter_village,
                       i.invited_player_id, i.created_at
                FROM alliance_invites i
                JOIN alliances a ON a.id = i.alliance_id
                JOIN players inv ON inv.id = i.inviter_id
                WHERE i.id = ?
                """,
                (rs, rowNum) -> new AllianceInviteInfo(
                        rs.getLong("id"),
                        rs.getLong("alliance_id"),
                        rs.getString("alliance_name"),
                        rs.getLong("inviter_id"),
                        rs.getString("inviter_village"),
                        rs.getLong("invited_player_id"),
                        rs.getLong("created_at")
                ),
                inviteId
        );
        return rows.stream().findFirst();
    }

    public List<AllianceInviteInfo> pendingAllianceInvites(long playerId) {
        return jdbcTemplate.query(
                """
                SELECT i.id, i.alliance_id, a.name AS alliance_name, i.inviter_id, inv.village_name AS inviter_village,
                       i.invited_player_id, i.created_at
                FROM alliance_invites i
                JOIN alliances a ON a.id = i.alliance_id
                JOIN players inv ON inv.id = i.inviter_id
                WHERE i.invited_player_id = ?
                ORDER BY i.created_at DESC
                """,
                (rs, rowNum) -> new AllianceInviteInfo(
                        rs.getLong("id"),
                        rs.getLong("alliance_id"),
                        rs.getString("alliance_name"),
                        rs.getLong("inviter_id"),
                        rs.getString("inviter_village"),
                        rs.getLong("invited_player_id"),
                        rs.getLong("created_at")
                ),
                playerId
        );
    }

    public void deleteAllianceInvite(long inviteId) {
        jdbcTemplate.update("DELETE FROM alliance_invites WHERE id = ?", inviteId);
    }

    public Optional<PlayerRecord> mostActiveAllTime() {
        List<PlayerRecord> rows = jdbcTemplate.query(
                """
                SELECT p.id, p.telegram_id, p.village_name, p.faction, p.city_level,
                       p.builders_count, p.has_cannon, p.has_armor, p.has_crossbow, p.equipped_armor, p.created_at
                FROM players p
                JOIN (
                    SELECT player_id, COUNT(*) AS c
                    FROM (
                        SELECT attacker_id AS player_id FROM battle_log
                        UNION ALL
                        SELECT defender_id AS player_id FROM battle_log
                    )
                    GROUP BY player_id
                    ORDER BY c DESC
                    LIMIT 1
                ) t ON t.player_id = p.id
                """,
                playerMapper()
        );
        return rows.stream().findFirst();
    }

    public Optional<PlayerRecord> mostAttackedAllTime() {
        List<PlayerRecord> rows = jdbcTemplate.query(
                """
                SELECT p.id, p.telegram_id, p.village_name, p.faction, p.city_level,
                       p.builders_count, p.has_cannon, p.has_armor, p.has_crossbow, p.equipped_armor, p.created_at
                FROM players p
                JOIN (
                    SELECT defender_id, COUNT(*) c
                    FROM battle_log
                    GROUP BY defender_id
                    ORDER BY c DESC
                    LIMIT 1
                ) t ON t.defender_id = p.id
                """,
                playerMapper()
        );
        return rows.stream().findFirst();
    }

    public List<FactionStats> factionStats() {
        return jdbcTemplate.query(
                """
                SELECT f.faction AS faction,
                       COALESCE(pc.players, 0) AS players,
                       COALESCE(wc.wins, 0) AS wins
                FROM (
                    SELECT 'KNIGHTS' AS faction UNION ALL
                    SELECT 'SAMURAI' UNION ALL
                    SELECT 'VIKINGS' UNION ALL
                    SELECT 'MONGOLS' UNION ALL
                    SELECT 'DESERT_DWELLERS' UNION ALL
                    SELECT 'AZTECS'
                ) f
                LEFT JOIN (
                    SELECT faction, COUNT(*) AS players
                    FROM players
                    GROUP BY faction
                ) pc ON pc.faction = f.faction
                LEFT JOIN (
                    SELECT p.faction AS faction, COUNT(*) AS wins
                    FROM battle_log b
                    JOIN players p ON p.id = b.winner_id
                    GROUP BY p.faction
                ) wc ON wc.faction = f.faction
                """,
                (rs, rowNum) -> new FactionStats(rs.getString("faction"), rs.getInt("players"), rs.getInt("wins"))
        );
    }

    public List<RecentBattle> recentBattles(int limit) {
        return jdbcTemplate.query(
                """
                SELECT attacker_id, defender_id, winner_id, created_at
                FROM battle_log
                ORDER BY created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new RecentBattle(
                        rs.getLong("attacker_id"),
                        rs.getLong("defender_id"),
                        rs.getLong("winner_id"),
                        rs.getLong("created_at")
                ),
                limit
        );
    }

    public PlayerBattleStats playerBattleStats(long playerId) {
        Integer wins = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM battle_log WHERE winner_id = ?",
                Integer.class,
                playerId
        );
        Integer losses = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM battle_log
                WHERE (attacker_id = ? OR defender_id = ?)
                  AND winner_id <> ?
                """,
                Integer.class,
                playerId, playerId, playerId
        );
        return new PlayerBattleStats(wins == null ? 0 : wins, losses == null ? 0 : losses);
    }

    public int countPlayersAtLeastCityLevel(int level) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM players WHERE city_level >= ?",
                Integer.class,
                level
        );
        return count == null ? 0 : count;
    }

    public void setServerState(String key, String value) {
        int updated = jdbcTemplate.update(
                "UPDATE server_state SET state_value = ?, updated_at = ? WHERE state_key = ?",
                value, Instant.now().toEpochMilli(), key
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO server_state(state_key, state_value, updated_at) VALUES (?, ?, ?)",
                    key, value, Instant.now().toEpochMilli()
            );
        }
    }

    public Optional<String> getServerState(String key) {
        List<String> rows = jdbcTemplate.query(
                "SELECT state_value FROM server_state WHERE state_key = ?",
                (rs, rowNum) -> rs.getString("state_value"),
                key
        );
        return rows.stream().findFirst();
    }

    public int buildInstant(long playerId, String buildingType) {
        return jdbcTemplate.update(
                "INSERT INTO buildings(player_id, building_type, level) VALUES (?, ?, 1)",
                playerId, buildingType
        );
    }

    public List<Build> findCompletedBuilds(long nowEpochMillis) {
        // Compatibility method for schedulers that still expect asynchronous build completion.
        // Current schema applies building upgrades instantly, so there is no build queue.
        return List.of();
    }

    public void finishBuildingUpgrades() {
        // Compatibility no-op: upgrades are applied immediately by buildInstant/upgradeBuildingInstant.
    }

    public int upgradeBuildingInstant(long playerId, String buildingType) {
        return jdbcTemplate.update(
                "UPDATE buildings SET level = CASE WHEN building_type = 'TOWN_HALL' AND level < 10 THEN level + 1 WHEN building_type <> 'TOWN_HALL' AND level < 3 THEN level + 1 ELSE level END WHERE player_id = ? AND building_type = ?",
                playerId, buildingType
        );
    }

    public void setBuildersCount(long playerId, int buildersCount) {
        jdbcTemplate.update("UPDATE players SET builders_count = ? WHERE id = ?", buildersCount, playerId);
    }

    public void setCityLevel(long playerId, int cityLevel) {
        jdbcTemplate.update("UPDATE players SET city_level = ? WHERE id = ?", cityLevel, playerId);
    }

    public void resetPlayerProgress(long playerId) {
        Optional<AllianceInfo> alliance = findAllianceByPlayerId(playerId);
        jdbcTemplate.update("DELETE FROM buildings WHERE player_id = ?", playerId);
        jdbcTemplate.update("DELETE FROM army WHERE player_id = ?", playerId);
        jdbcTemplate.update("DELETE FROM inventory WHERE player_id = ?", playerId);
        jdbcTemplate.update("DELETE FROM battles WHERE attacker_id = ? OR defender_id = ?", playerId, playerId);
        jdbcTemplate.update("DELETE FROM market_listings WHERE seller_id = ? OR highest_bidder_id = ?", playerId, playerId);
        alliance.ifPresent(a -> removeAllianceMember(a.id(), playerId));
        jdbcTemplate.update("DELETE FROM vassals WHERE vassal_id = ? OR lord_id = ?", playerId, playerId);
        jdbcTemplate.update("DELETE FROM player_state WHERE player_id = ?", playerId);
        jdbcTemplate.update("DELETE FROM alliance_invites WHERE inviter_id = ? OR invited_player_id = ?", playerId, playerId);

        jdbcTemplate.update(
                """
                UPDATE resources
                SET wood = 0, stone = 0, food = 0, iron = 0, gold = 0, mana = 0, alcohol = 0
                WHERE player_id = ?
                """,
                playerId
        );

        jdbcTemplate.update(
                "UPDATE players SET city_level = 1, builders_count = 1, has_cannon = 0, has_armor = 0, has_crossbow = 0, equipped_armor = NULL WHERE id = ?",
                playerId
        );

        jdbcTemplate.update(
                "INSERT INTO buildings(player_id, building_type, level) VALUES (?, 'TOWN_HALL', 1)",
                playerId
        );
    }

    public long createBattle(long attackerId, long defenderId, int attackerHp, int defenderHp, int maxRounds) {
        return createBattle(attackerId, defenderId, attackerHp, defenderHp, maxRounds, "WAITING_ACTIONS");
    }

    public long createBattle(long attackerId, long defenderId, int attackerHp, int defenderHp, int maxRounds, String status) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO battles(attacker_id, defender_id, attacker_hp, defender_hp, attacker_max_hp, defender_max_hp,
                                    current_round, max_rounds, attacker_action, defender_action, status, created_at,
                                    round_started_at, history)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, NULL, NULL, ?, ?, NULL, '')
                RETURNING id
                """,
                Long.class,
                attackerId, defenderId, attackerHp, defenderHp, attackerHp, defenderHp, maxRounds, status, Instant.now().toEpochMilli()
        );
        if (id == null) {
            throw new IllegalStateException("Failed to create battle");
        }
        return id;
    }

    public Optional<BattleRecord> findBattle(long battleId) {
        List<BattleRecord> rows = jdbcTemplate.query(
                """
                SELECT id, attacker_id, defender_id, attacker_hp, defender_hp, attacker_max_hp, defender_max_hp,
                       current_round, max_rounds, attacker_action, defender_action, status, created_at, round_started_at,
                       history
                FROM battles
                WHERE id = ?
                """,
                battleMapper(),
                battleId
        );
        return rows.stream().findFirst();
    }

    public List<BattleRecord> activeBattlesForPlayer(long playerId) {
        return jdbcTemplate.query(
                """
                SELECT id, attacker_id, defender_id, attacker_hp, defender_hp, attacker_max_hp, defender_max_hp,
                       current_round, max_rounds, attacker_action, defender_action, status, created_at, round_started_at,
                       history
                FROM battles
                WHERE status <> 'FINISHED'
                  AND (attacker_id = ? OR defender_id = ?)
                ORDER BY id DESC
                """,
                battleMapper(),
                playerId, playerId
        );
    }

    public List<BattleRecord> waitingBattles() {
        return jdbcTemplate.query(
                """
                SELECT id, attacker_id, defender_id, attacker_hp, defender_hp, attacker_max_hp, defender_max_hp,
                       current_round, max_rounds, attacker_action, defender_action, status, created_at, round_started_at,
                       history
                FROM battles
                WHERE status = 'WAITING_ACTIONS'
                ORDER BY id ASC
                """,
                battleMapper()
        );
    }

    public List<BattleRecord> waitingChallenges() {
        return jdbcTemplate.query(
                """
                SELECT id, attacker_id, defender_id, attacker_hp, defender_hp, attacker_max_hp, defender_max_hp,
                       current_round, max_rounds, attacker_action, defender_action, status, created_at, round_started_at,
                       history
                FROM battles
                WHERE status = 'WAITING_CHALLENGE'
                ORDER BY id ASC
                """,
                battleMapper()
        );
    }

    public void setBattleStatus(long battleId, String status) {
        jdbcTemplate.update("UPDATE battles SET status = ? WHERE id = ?", status, battleId);
    }

    public void startBattleRound(long battleId, int round, long startedAt) {
        jdbcTemplate.update(
                "UPDATE battles SET current_round = ?, round_started_at = ?, attacker_action = NULL, defender_action = NULL WHERE id = ?",
                round, startedAt, battleId
        );
    }

    public void setBattleAction(long battleId, boolean attackerSide, String action) {
        String col = attackerSide ? "attacker_action" : "defender_action";
        jdbcTemplate.update("UPDATE battles SET " + col + " = ? WHERE id = ?", action, battleId);
    }

    public void setBattleHp(long battleId, int attackerHp, int defenderHp, String historyAppend) {
        jdbcTemplate.update(
                """
                UPDATE battles
                SET attacker_hp = ?, defender_hp = ?,
                    history = CASE WHEN history IS NULL OR history = '' THEN ? ELSE history || '\n' || ? END,
                    attacker_action = NULL, defender_action = NULL
                WHERE id = ?
                """,
                attackerHp, defenderHp,
                historyAppend, historyAppend, battleId
        );
    }

    public void finishBattle(long battleId, String historyAppend) {
        jdbcTemplate.update(
                """
                UPDATE battles
                SET status = 'FINISHED',
                    history = CASE WHEN history IS NULL OR history = '' THEN ? ELSE history || '\n' || ? END
                WHERE id = ?
                """,
                historyAppend, historyAppend, battleId
        );
    }

    private RowMapper<PlayerRecord> playerMapper() {
        return (rs, rowNum) -> new PlayerRecord(
                rs.getLong("id"),
                rs.getLong("telegram_id"),
                rs.getString("village_name"),
                Faction.valueOf(rs.getString("faction")),
                rs.getInt("city_level"),
                rs.getInt("builders_count"),
                rs.getInt("has_cannon") == 1,
                rs.getInt("has_armor") == 1,
                rs.getInt("has_crossbow") == 1,
                nullableString(rs, "equipped_armor"),
                rs.getLong("created_at")
        );
    }

    private RowMapper<BattleRecord> battleMapper() {
        return (rs, rowNum) -> new BattleRecord(
                rs.getLong("id"),
                rs.getLong("attacker_id"),
                rs.getLong("defender_id"),
                rs.getInt("attacker_hp"),
                rs.getInt("defender_hp"),
                rs.getInt("attacker_max_hp"),
                rs.getInt("defender_max_hp"),
                rs.getInt("current_round"),
                rs.getInt("max_rounds"),
                rs.getString("attacker_action"),
                rs.getString("defender_action"),
                rs.getString("status"),
                rs.getLong("created_at"),
                nullableLong(rs, "round_started_at"),
                rs.getString("history")
        );
    }

    private RowMapper<MarketListing> marketListingMapper() {
        return (rs, rowNum) -> new MarketListing(
                rs.getLong("id"),
                rs.getLong("seller_id"),
                rs.getString("item_type"),
                rs.getInt("price"),
                rs.getInt("is_auction") == 1,
                nullableLong(rs, "auction_ends_at"),
                nullableLong(rs, "highest_bidder_id"),
                nullableInteger(rs, "highest_bid")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private String resourceColumn(String resourceKey) {
        return switch (resourceKey) {
            case "WOOD" -> "wood";
            case "STONE" -> "stone";
            case "FOOD" -> "food";
            case "IRON" -> "iron";
            case "GOLD" -> "gold";
            case "MANA" -> "mana";
            case "ALCOHOL" -> "alcohol";
            default -> throw new IllegalArgumentException("Unknown resource key: " + resourceKey);
        };
    }

    private String nullableString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private int lumbermillBonus(int level) {
        return switch (level) {
            case 1 -> 8;
            case 2 -> 15;
            case 3 -> 25;
            default -> 0;
        };
    }

    private int mineStoneBonus(int level) {
        return switch (level) {
            case 1 -> 4;
            case 2 -> 8;
            case 3 -> 12;
            default -> 0;
        };
    }

    private int mineIronBonus(int level) {
        return switch (level) {
            case 1 -> 3;
            case 2 -> 6;
            case 3 -> 10;
            default -> 0;
        };
    }

    private int farmBonus(int level) {
        return switch (level) {
            case 1 -> 6;
            case 2 -> 12;
            case 3 -> 20;
            default -> 0;
        };
    }

    private int tavernBonus(int level) {
        return switch (level) {
            case 1 -> 2;
            case 2 -> 5;
            case 3 -> 9;
            default -> 0;
        };
    }

    private int templeBonus(int level) {
        return switch (level) {
            case 1 -> 2;
            case 2 -> 5;
            case 3 -> 9;
            default -> 0;
        };
    }

    private int marketBonus(int level) {
        return switch (level) {
            case 1 -> 2;
            case 2 -> 5;
            case 3 -> 9;
            default -> 0;
        };
    }
}
