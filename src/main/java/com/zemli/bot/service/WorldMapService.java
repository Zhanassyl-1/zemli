package com.zemli.bot.service;

import com.zemli.bot.dao.GameDao;
import com.zemli.bot.model.Faction;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class WorldMapService {

    private static final int MIN_CAPITAL_DISTANCE = 10;
    private static final int DEFAULT_VIEW_RADIUS = 3;

    private static final Map<Faction, GameDao.Point> SPAWNS = new EnumMap<>(Faction.class);

    static {
        SPAWNS.put(Faction.KNIGHTS, new GameDao.Point(0, 0));
        SPAWNS.put(Faction.VIKINGS, new GameDao.Point(0, 300));
        SPAWNS.put(Faction.MONGOLS, new GameDao.Point(300, 0));
        SPAWNS.put(Faction.DESERT_DWELLERS, new GameDao.Point(0, -300));
        SPAWNS.put(Faction.AZTECS, new GameDao.Point(-300, 0));
        SPAWNS.put(Faction.SAMURAI, new GameDao.Point(300, 300));
    }

    private final GameDao gameDao;

    public WorldMapService(GameDao gameDao) {
        this.gameDao = gameDao;
    }

    public GameDao.Point ensureCapital(long playerId, Faction faction) {
        Optional<GameDao.Point> existing = gameDao.loadCapital(playerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        GameDao.Point spawn = SPAWNS.getOrDefault(faction, new GameDao.Point(0, 0));
        GameDao.Point found = findFreePoint(spawn);
        gameDao.saveCapital(playerId, found.x(), found.y());
        return found;
    }

    public Optional<GameDao.Point> capitalOf(long playerId) {
        return gameDao.loadCapital(playerId);
    }

    public String renderMap(long playerId, int centerX, int centerY, int radius) {
        GameDao.Point capital = gameDao.loadCapital(playerId).orElse(new GameDao.Point(0, 0));
        StringBuilder sb = new StringBuilder("🗺️ Карта ").append(centerX).append(",").append(centerY).append("\n");
        for (int y = centerY - radius; y <= centerY + radius; y++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                boolean visible = chebyshevDistance(capital.x(), capital.y(), x, y) <= DEFAULT_VIEW_RADIUS;
                if (!visible) {
                    sb.append("⬛");
                    continue;
                }
                if (x == capital.x() && y == capital.y()) {
                    sb.append("🏰");
                    continue;
                }
                sb.append(symbolForBiome(biomeAt(x, y)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public List<MapCellDto> cellsInRadius(long requesterPlayerId, int centerX, int centerY, int radius) {
        GameDao.Point capital = gameDao.loadCapital(requesterPlayerId).orElse(new GameDao.Point(0, 0));
        Map<String, GameDao.CapitalPoint> capitals = new HashMap<>();
        for (GameDao.CapitalPoint c : gameDao.loadAllCapitalsWithOwners()) {
            capitals.put(c.x() + ":" + c.y(), c);
        }

        List<MapCellDto> cells = new java.util.ArrayList<>();
        for (int y = centerY - radius; y <= centerY + radius; y++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                boolean visible = chebyshevDistance(capital.x(), capital.y(), x, y) <= DEFAULT_VIEW_RADIUS;
                if (!visible) {
                    cells.add(new MapCellDto(x, y, "fog", null, null, null));
                    continue;
                }
                Biome biome = biomeAt(x, y);
                String resource = resourceForBiome(biome);
                GameDao.CapitalPoint ownerCap = capitals.get(x + ":" + y);
                String building = ownerCap == null ? null : "capitol";
                String owner = ownerCap == null ? null : ("player" + ownerCap.playerId());
                cells.add(new MapCellDto(x, y, biomeToApi(biome), building, owner, resource));
            }
        }
        return cells;
    }

    public boolean canBuild(long playerId, int x, int y) {
        GameDao.Point capital = gameDao.loadCapital(playerId).orElse(null);
        if (capital == null) {
            return false;
        }
        if (chebyshevDistance(capital.x(), capital.y(), x, y) > 5) {
            return false;
        }
        Biome biome = biomeAt(x, y);
        return biome != Biome.SEA;
    }

    public String legend() {
        return """
                📘 Легенда карты
                🟩 Равнина
                🌳 Лес (🪵)
                🌴 Джунгли (🧪)
                🟨 Пустыня (💰)
                🟫 Горы (⚔️)
                🟦 Море (🐟)
                ⬜ Лёд (❄️)
                🟪 Болото (🧪)
                🟥 Вулкан (🔥)
                🏰 Ратуша
                ⬛ Туман войны
                """;
    }

    private GameDao.Point findFreePoint(GameDao.Point start) {
        List<GameDao.Point> capitals = gameDao.loadAllCapitals();
        if (isFarEnough(start, capitals)) {
            return start;
        }
        int x = start.x();
        int y = start.y();
        int stepLen = 1;
        int dir = 0;
        int[] dx = {1, 0, -1, 0};
        int[] dy = {0, 1, 0, -1};
        for (int ring = 0; ring < 4000; ring++) {
            for (int rep = 0; rep < 2; rep++) {
                for (int i = 0; i < stepLen; i++) {
                    x += dx[dir];
                    y += dy[dir];
                    GameDao.Point point = new GameDao.Point(x, y);
                    if (isFarEnough(point, capitals)) {
                        return point;
                    }
                }
                dir = (dir + 1) % 4;
            }
            stepLen++;
        }
        return new GameDao.Point(start.x() + 5000, start.y() + 5000);
    }

    private boolean isFarEnough(GameDao.Point point, List<GameDao.Point> capitals) {
        for (GameDao.Point taken : capitals) {
            if (chebyshevDistance(point.x(), point.y(), taken.x(), taken.y()) < MIN_CAPITAL_DISTANCE) {
                return false;
            }
        }
        return true;
    }

    private int chebyshevDistance(int x1, int y1, int x2, int y2) {
        return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
    }

    private Biome biomeAt(int x, int y) {
        double temp = noise(x, y, 11);
        double humid = noise(x + 1000, y, 23);
        double height = noise(x, y + 1000, 37);

        if (height < 0.20) return Biome.SEA;
        if (height < 0.25) return Biome.PLAINS;
        if (height > 0.88 && temp > 0.6) return Biome.VOLCANO;

        if (temp > 0.7) {
            return humid > 0.6 ? Biome.JUNGLE : Biome.DESERT;
        }
        if (temp > 0.3) {
            if (humid > 0.65) return Biome.FOREST;
            if (height > 0.75) return Biome.MOUNTAINS;
            return Biome.PLAINS;
        }
        if (temp < 0.2) return Biome.ICE;
        return Biome.SWAMP;
    }

    private String symbolForBiome(Biome biome) {
        return switch (biome) {
            case PLAINS -> "🟩";
            case FOREST -> "🌳";
            case JUNGLE -> "🌴";
            case DESERT -> "🟨";
            case MOUNTAINS -> "🟫";
            case SEA -> "🟦";
            case ICE -> "⬜";
            case SWAMP -> "🟪";
            case VOLCANO -> "🟥";
        };
    }

    private String resourceForBiome(Biome biome) {
        return switch (biome) {
            case FOREST -> "wood";
            case JUNGLE -> "mana";
            case DESERT -> "gold";
            case MOUNTAINS -> "iron";
            case SEA -> "fish";
            case ICE -> "cold";
            case SWAMP -> "poison";
            case VOLCANO -> "fire";
            case PLAINS -> "food";
        };
    }

    private String biomeToApi(Biome biome) {
        return switch (biome) {
            case PLAINS -> "plain";
            case FOREST -> "forest";
            case JUNGLE -> "jungle";
            case DESERT -> "desert";
            case MOUNTAINS -> "mountain";
            case SEA -> "sea";
            case ICE -> "ice";
            case SWAMP -> "swamp";
            case VOLCANO -> "volcano";
        };
    }

    private double noise(int x, int y, int seed) {
        long z = 0x9E3779B97F4A7C15L;
        z ^= ((long) x + 0x632BE5ABL) * 0xBF58476D1CE4E5B9L;
        z ^= ((long) y + 0x85157AF5L) * 0x94D049BB133111EBL;
        z ^= ((long) seed) * 0xD6E8FEB86659FD93L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        long v = z & Long.MAX_VALUE;
        return (v % 1_000_000L) / 1_000_000.0;
    }

    private enum Biome {
        PLAINS, FOREST, JUNGLE, DESERT, MOUNTAINS, SEA, ICE, SWAMP, VOLCANO
    }

    public record MapCellDto(
            int x,
            int y,
            String biome,
            String building,
            String owner,
            String resource
    ) {
    }
}
