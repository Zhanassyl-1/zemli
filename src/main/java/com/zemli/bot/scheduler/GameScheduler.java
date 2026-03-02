package com.zemli.bot.scheduler;

import com.zemli.bot.bot.ZemliTelegramBot;
import com.zemli.bot.dao.GameDao;
import com.zemli.bot.model.MarketListing;
import com.zemli.bot.model.DailyEventType;
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

    public GameScheduler(GameDao gameDao, GroupAnnouncementService announcementService, ZemliTelegramBot bot) {
        this.gameDao = gameDao;
        this.announcementService = announcementService;
        this.bot = bot;
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "${game.time-zone:UTC}")
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

    @Scheduled(cron = "0 0 * * * *", zone = "${game.time-zone:UTC}")
    public void moraleDecayTick() {
        int affected = gameDao.applyInactivityMoraleDecay();
        if (affected > 0) {
            log.info("Morale inactivity decay applied for players={}", affected);
        }
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

    @Scheduled(cron = "0 */5 * * * *", zone = "${game.time-zone:UTC}")
    public void expireTradeOffers() {
        int expired = gameDao.expireTradeOffersAndRefund();
        if (expired > 0) {
            log.info("Expired trade offers refunded={}", expired);
        }
    }

    @Scheduled(cron = "0 0 */2 * * *", zone = "${game.time-zone:UTC}")
    public void spawnTravelingMerchant() {
        long until = System.currentTimeMillis() + 30L * 60L * 1000L;
        gameDao.setServerState("TRAVEL_MERCHANT_UNTIL", String.valueOf(until));
        bot.sendGroupMessageAsync(
                "🧙 СТРАНСТВУЮЩИЙ ТОРГОВЕЦ появился в землях!\n" +
                        "Предлагает (только 30 минут):\n" +
                        "🪵1000 за 💰50\n" +
                        "⚔️200 за 💰80\n" +
                        "🧪50 за 💰200\n" +
                        "[ 🛒 Купить у торговца → @" + bot.getBotUsername() + " ]"
        );
    }

    @Scheduled(cron = "*/5 * * * * *", zone = "${game.time-zone:UTC}")
    public void battleTicks() {
        bot.processBattleTicks();
    }
}
