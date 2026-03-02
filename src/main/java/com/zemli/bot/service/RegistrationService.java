package com.zemli.bot.service;

import com.zemli.bot.dao.GameDao;
import com.zemli.bot.model.Faction;
import com.zemli.bot.model.PlayerRecord;
import com.zemli.bot.model.RegistrationDraft;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RegistrationService {

    public enum RegistrationState {
        WAITING_VILLAGE_NAME,
        WAITING_FACTION,
        REGISTERED
    }

    private final GameDao gameDao;
    private final Map<Long, RegistrationDraft> registrationDrafts = new ConcurrentHashMap<>();
    private final Map<Long, RegistrationState> states = new ConcurrentHashMap<>();

    public RegistrationService(GameDao gameDao) {
        this.gameDao = gameDao;
    }

    public Optional<PlayerRecord> findRegistered(long telegramId) {
        Optional<PlayerRecord> player = gameDao.findPlayerByTelegramId(telegramId);
        if (player.isPresent()) {
            states.put(telegramId, RegistrationState.REGISTERED);
        }
        return player;
    }

    public void begin(long telegramId) {
        registrationDrafts.remove(telegramId);
        states.put(telegramId, RegistrationState.WAITING_VILLAGE_NAME);
    }

    public boolean hasDraft(long telegramId) {
        return registrationDrafts.containsKey(telegramId);
    }

    public boolean isWaitingVillageName(long telegramId) {
        return states.get(telegramId) == RegistrationState.WAITING_VILLAGE_NAME;
    }

    public boolean isWaitingFaction(long telegramId) {
        return states.get(telegramId) == RegistrationState.WAITING_FACTION;
    }

    public boolean setVillageName(long telegramId, String villageName) {
        String normalized = villageName == null ? "" : villageName.trim();
        if (normalized.length() < 3 || normalized.length() > 32) {
            return false;
        }
        registrationDrafts.put(telegramId, new RegistrationDraft(normalized));
        states.put(telegramId, RegistrationState.WAITING_FACTION);
        return true;
    }

    public Optional<RegistrationDraft> getDraft(long telegramId) {
        return Optional.ofNullable(registrationDrafts.get(telegramId));
    }

    public PlayerRecord complete(long telegramId, Faction faction) {
        RegistrationDraft draft = registrationDrafts.get(telegramId);
        if (draft == null) {
            throw new IllegalStateException("Village name is not set");
        }
        PlayerRecord created = gameDao.createPlayer(telegramId, draft.villageName(), faction);
        registrationDrafts.remove(telegramId);
        states.put(telegramId, RegistrationState.REGISTERED);
        return created;
    }
}
