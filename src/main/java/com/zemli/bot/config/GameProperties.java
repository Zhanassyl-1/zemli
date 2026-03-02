package com.zemli.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties(prefix = "game")
public class GameProperties {

    private String databaseUrl;
    private long groupChatId;
    private String timeZone = "UTC";
    private String adminIds = "";

    public String getDatabaseUrl() {
        return databaseUrl;
    }

    public void setDatabaseUrl(String databaseUrl) {
        this.databaseUrl = databaseUrl;
    }

    public long getGroupChatId() {
        return groupChatId;
    }

    public void setGroupChatId(long groupChatId) {
        this.groupChatId = groupChatId;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public String getAdminIds() {
        return adminIds;
    }

    public void setAdminIds(String adminIds) {
        this.adminIds = adminIds;
    }

    public Set<Long> adminIdSet() {
        if (adminIds == null || adminIds.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(adminIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toSet());
    }
}
