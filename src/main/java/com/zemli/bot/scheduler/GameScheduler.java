package com.zemli.bot.scheduler;

import com.zemli.bot.bot.ZemliTelegramBot;
import com.zemli.bot.dao.GameDao;
import com.zemli.bot.model.MarketListing;
import com.zemli.bot.model.DailyEventType;
import com.zemli.bot.service.GameCatalog;
import com.zemli.bot.service.GroupAnnouncementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GameScheduler {

    private static final Logger log = LoggerFactory.getLogger(GameScheduler.class);

    private final GameDao gameDao;
    private final GroupAnnouncementService announcementService;
    private final ZemliTelegramBot bot;
    private final GameCatalog gameCatalog;

    public GameScheduler(GameDao gameDao, GroupAnnouncementService announcementService, ZemliTelegramBot bot, GameCatalog gameCatalog) {
        this.gameDao = gameDao;
        this.announcementService = announcementService;
        this.bot = bot;
        this.gameCatalog = gameCatalog;
    }

    @Scheduled(cron = "0 0 */4 * * *", zone = "${game.time-zone:UTC}")
    public void passiveIncomeTick() {
        double mul = 1.0;
        String today = DailyEventType.todayUtc();
        String eventDate = gameDao.getServerState(DailyEventType.STATE_KEY_EVENT_DATE).orElse("");
        if (today.equals(eventDate)) {
            DailyEventType event = DailyEventType.parse(gameDao.getServerState(DailyEventType.STATE_KEY_EVENT).orElse(null));
            if (event == DailyEventType.HARVEST_DAY) {
                mul = 1.2;
            }
        }
        int affected = gameDao.applyPassiveIncomeTick(mul);
        log.info("Passive resource tick completed, players affected={}", affected);
    }

    @Scheduled(cron = "0 0 20 * * *", zone = "${game.time-zone:UTC}")
    public void dailyDigest() {
        announcementService.sendDailyDigest();
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "${game.time-zone:UTC}")
    public void morningEvent() {
        announcementService.sendMorningEvent();
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "${game.time-zone:UTC}")
    public void finishAuctions() {
        List<MarketListing> ended = gameDao.findEndedAuctions();
        for (MarketListing listing : ended) {
            gameDao.closeAuction(listing);
            gameDao.appendDailyLog("AUCTION", "🏦 Аукцион завершён: " + listing.itemType());
        }
        if (!ended.isEmpty()) {
            log.info("Closed auctions={}", ended.size());
        }
    }

    @Scheduled(cron = "0 * * * * *", zone = "${game.time-zone:UTC}")
    public void finishBuildingUpgrades() {
        long now = System.currentTimeMillis();
        var completed = gameDao.findCompletedBuilds(now);
        int done = gameDao.finishBuildingUpgrades();
        if (done > 0) {
            for (var c : completed) {
                String title = gameCatalog.buildings().containsKey(c.buildingType())
                        ? gameCatalog.buildings().get(c.buildingType()).title()
                        : c.buildingType();
                bot.sendText(c.telegramId(), "🏗️ " + title + " улучшено до ур." + c.toLevel());
                announcementService.announceBuildingCompleted(c.playerId(), c.villageName(), title);
                gameDao.appendDailyLog("BUILDING_DONE", c.villageName() + " построил/улучшил " + title + " до ур." + c.toLevel());
                if ("TOWN_HALL".equals(c.buildingType())) {
                    announcementService.announceCityLevelUp(c.villageName(), c.toLevel());
                }
            }
            log.info("Finished building upgrades={}", done);
        }
    }

    @Scheduled(cron = "*/5 * * * * *", zone = "${game.time-zone:UTC}")
    public void battleTicks() {
        bot.processBattleTicks();
    }
}
