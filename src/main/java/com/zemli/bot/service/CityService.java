package com.zemli.bot.service;

import com.zemli.bot.config.GameProperties;
import com.zemli.bot.dao.GameDao;
import com.zemli.bot.model.CityOverview;
import com.zemli.bot.model.KeyValueAmount;
import com.zemli.bot.model.PlayerRecord;
import com.zemli.bot.model.ResourcesRecord;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class CityService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final GameDao gameDao;
    private final GameCatalog gameCatalog;
    private final ZoneId zoneId;

    public CityService(GameDao gameDao, GameProperties properties, GameCatalog gameCatalog) {
        this.gameDao = gameDao;
        this.gameCatalog = gameCatalog;
        this.zoneId = ZoneId.of(properties.getTimeZone());
    }

    public CityOverview overview(long telegramId) {
        PlayerRecord player = gameDao.findPlayerByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalStateException("Player not found"));
        ResourcesRecord resources = gameDao.loadResources(player.id());
        List<KeyValueAmount> army = gameDao.loadArmy(player.id());
        List<KeyValueAmount> buildings = gameDao.loadBuildingStates(player.id()).stream()
                .map(b -> new KeyValueAmount(b.buildingType(), b.level()))
                .toList();
        List<KeyValueAmount> inventory = gameDao.loadInventory(player.id());

        return new CityOverview(
                player,
                resources,
                army,
                gameDao.calculateTotalArmyPower(player.id(), gameCatalog, player.faction()),
                buildings,
                inventory,
                nextPassiveTick(),
                gameDao.rankByCityLevel(player.id())
        );
    }

    public String format(CityOverview city) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏰 ").append(city.player().villageName())
                .append(" | ").append(city.player().faction().getTitle())
                .append(" | Уровень города: ").append(city.player().cityLevel()).append("\n\n");

        sb.append("📦 Ресурсы\n");
        sb.append("Дерево: ").append(city.resources().wood()).append("\n");
        sb.append("Камень: ").append(city.resources().stone()).append("\n");
        sb.append("Еда: ").append(city.resources().food()).append("\n");
        sb.append("Железо: ").append(city.resources().iron()).append("\n");
        sb.append("Золото: ").append(city.resources().gold()).append("\n");
        sb.append("Манна: ").append(city.resources().mana()).append("\n");
        sb.append("Алкоголь: ").append(city.resources().alcohol()).append("\n\n");

        sb.append("⚔️ Армия\n");
        if (city.army().isEmpty()) {
            sb.append("Пока нет юнитов\n");
        } else {
            for (KeyValueAmount unit : city.army()) {
                sb.append("- ").append(unit.type()).append(": ").append(unit.quantity()).append("\n");
            }
        }
        sb.append("Общая мощь: ").append(city.totalArmyPower()).append("\n");
        sb.append("Статус атаки: готов к бою\n");

        sb.append("\n🏗️ Здания\n");
        if (city.buildings().isEmpty()) {
            sb.append("Нет построек\n");
        } else {
            for (KeyValueAmount building : city.buildings()) {
                sb.append("- ").append(building.type()).append(" ур.").append(building.quantity()).append("\n");
            }
        }

        sb.append("\n🎒 Инвентарь\n");
        if (city.inventory().isEmpty()) {
            sb.append("Пусто\n");
        } else {
            for (KeyValueAmount item : city.inventory()) {
                sb.append("- ").append(item.type()).append(": ").append(item.quantity()).append("\n");
            }
        }

        sb.append("\n⏱️ Следующая пассивная добыча: ").append(formatEpoch(city.nextPassiveTickEpoch())).append("\n");
        sb.append("🏆 Место в рейтинге: ").append(city.rankingPosition());

        return sb.toString();
    }

    private long nextPassiveTick() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        int minuteBlock = (now.getMinute() / 10) * 10;
        ZonedDateTime next = now.withMinute(minuteBlock).withSecond(0).withNano(0).plusMinutes(10);
        return next.toInstant().toEpochMilli();
    }

    private String formatEpoch(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(DATE_TIME);
    }
}
