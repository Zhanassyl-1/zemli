package com.zemli.bot.model;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ThreadLocalRandom;

public enum DailyEventType {
    HARVEST_DAY("🌾 День урожая — все получают +20% к добыче сегодня"),
    WAR_DAY("⚔️ День войны — атаки дают +10% ресурсов сегодня"),
    PEACE_DAY("🛡️ День мира — защита всех городов +15% сегодня"),
    TRADE_DAY("💰 Торговый день — комиссия аукциона снижена до 0 сегодня"),
    SCOUTING_DAY("🔍 День разведки — шанс найти редкий предмет ×2 сегодня"),
    BUILDER_DAY("🏗️ День строителя — строительство в 2 раза быстрее сегодня");

    public static final String STATE_KEY_EVENT = "DAILY_EVENT";
    public static final String STATE_KEY_EVENT_DATE = "DAILY_EVENT_DATE";

    private final String text;

    DailyEventType(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    public static DailyEventType random() {
        DailyEventType[] all = values();
        return all[ThreadLocalRandom.current().nextInt(all.length)];
    }

    public static String todayUtc() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    public static DailyEventType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DailyEventType.valueOf(raw);
        } catch (Exception ignored) {
            return null;
        }
    }
}
