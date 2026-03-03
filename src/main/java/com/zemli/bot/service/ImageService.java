package com.zemli.bot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Service
public class ImageService {
    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    private final Map<String, String> victoryImages = new HashMap<>();
    private final Map<String, String> defeatImages = new HashMap<>();

    public ImageService() {
        initVictoryImages();
        initDefeatImages();
    }

    private void initVictoryImages() {
        victoryImages.put("AZTEC_KNIGHTS", "/images/heroes/aztec_vs_knights_win.png");
        victoryImages.put("AZTEC_SAMURAI", "/images/heroes/aztec_vs_samurais_win.png");
        victoryImages.put("AZTEC_VIKING", "/images/heroes/aztec_vs_vikings_win.png");
        victoryImages.put("AZTEC_MONGOL", "/images/heroes/aztec_vs_mongols_win.png");
        victoryImages.put("AZTEC_ARABIAN", "/images/heroes/aztec_vs_arabians_win.png");

        victoryImages.put("ARABIAN_KNIGHTS", "/images/heroes/arabian_vs_knights_win.png");
        victoryImages.put("ARABIAN_SAMURAI", "/images/heroes/arabian_vs_samurais_win.png");
        victoryImages.put("ARABIAN_VIKING", "/images/heroes/arabian_vs_vikings_win.png");
        victoryImages.put("ARABIAN_MONGOL", "/images/heroes/arabian_vs_mongols_win.png");
        victoryImages.put("ARABIAN_AZTEC", "/images/heroes/arabian_vs_aztecs_win.png");

        victoryImages.put("KNIGHT_SAMURAI", "/images/heroes/knight_vs_samurais_win.png");
        victoryImages.put("KNIGHT_VIKING", "/images/heroes/knight_vs_vikings_win.png");
        victoryImages.put("KNIGHT_MONGOL", "/images/heroes/knight_vs_mongols_win.png");
        victoryImages.put("KNIGHT_ARABIAN", "/images/heroes/knight_vs_arabians_win.png");
        victoryImages.put("KNIGHT_AZTEC", "/images/heroes/knight_vs_aztecs_win.png");

        victoryImages.put("SAMURAI_KNIGHTS", "/images/heroes/samurai_vs_knights_win.png");
        victoryImages.put("SAMURAI_VIKING", "/images/heroes/samurai_vs_vikings_win.png");
        victoryImages.put("SAMURAI_MONGOL", "/images/heroes/samurai_vs_mongols_win.png");
        victoryImages.put("SAMURAI_ARABIAN", "/images/heroes/samurai_vs_arabians_win.png");
        victoryImages.put("SAMURAI_AZTEC", "/images/heroes/samurai_vs_aztecs_win.png");

        victoryImages.put("VIKING_KNIGHTS", "/images/heroes/viking_vs_knights_win.png");
        victoryImages.put("VIKING_SAMURAI", "/images/heroes/viking_vs_samurais_win.png");
        victoryImages.put("VIKING_MONGOL", "/images/heroes/viking_vs_mongols_win.png");
        victoryImages.put("VIKING_ARABIAN", "/images/heroes/viking_vs_arabians_win.png");
        victoryImages.put("VIKING_AZTEC", "/images/heroes/viking_vs_aztecs_win.png");

        victoryImages.put("MONGOL_KNIGHTS", "/images/heroes/mongol_vs_knights_win.png");
        victoryImages.put("MONGOL_SAMURAI", "/images/heroes/mongol_vs_samurais_win.png");
        victoryImages.put("MONGOL_VIKING", "/images/heroes/mongol_vs_vikings_win.png");
        victoryImages.put("MONGOL_ARABIAN", "/images/heroes/mongol_vs_arabians_win.png");
        victoryImages.put("MONGOL_AZTEC", "/images/heroes/mongol_vs_aztecs_win.png");
    }

    private void initDefeatImages() {
        defeatImages.put("KNIGHT", "/images/heroes/knight_defeat.png");
        defeatImages.put("SAMURAI", "/images/heroes/samurai_defeat.png");
        defeatImages.put("VIKING", "/images/heroes/viking_defeat.png");
        defeatImages.put("MONGOL", "/images/heroes/mongol_defeat.png");
        defeatImages.put("ARABIAN", "/images/heroes/arabian_defeat.png");
        defeatImages.put("AZTEC", "/images/heroes/aztec_defeat.png");
    }

    public SendPhoto getVictoryImage(String winner, String loser, String chatId) {
        String key = winner + "_" + loser;
        String imagePath = victoryImages.getOrDefault(key, "/images/heroes/universal_victory.png");
        return buildSendPhoto(chatId, imagePath, "🏆 " + winner + " ПОБЕДИЛИ " + loser + "!");
    }

    public SendPhoto getDefeatImage(String race, String chatId) {
        String imagePath = defeatImages.getOrDefault(race, "/images/heroes/universal_defeat.png");
        return buildSendPhoto(chatId, imagePath, "💔 ВАША АРМИЯ РАЗГРОМЛЕНА...");
    }

    public SendPhoto getUniversalVictory(String chatId) {
        return buildSendPhoto(chatId, "/images/heroes/universal_victory.png", "🏆 ПОБЕДА!");
    }

    public SendPhoto getUniversalDefeat(String chatId) {
        return buildSendPhoto(chatId, "/images/heroes/universal_defeat.png", "💔 ПОРАЖЕНИЕ...");
    }

    public SendPhoto getArtifactImage(String chatId, String artifactName) {
        return buildSendPhoto(chatId, "/images/heroes/universal_victory.png", "💎 Найден артефакт: " + artifactName);
    }

    private SendPhoto buildSendPhoto(String chatId, String imagePath, String caption) {
        InputStream imageStream = getClass().getResourceAsStream(imagePath);
        if (imageStream == null) {
            log.error("❌ КАРТИНКА НЕ НАЙДЕНА: {}", imagePath);
            return null;
        }
        InputFile inputFile = new InputFile(imageStream, imagePath.substring(imagePath.lastIndexOf('/') + 1));
        SendPhoto sendPhoto = new SendPhoto();
        sendPhoto.setChatId(chatId);
        sendPhoto.setPhoto(inputFile);
        sendPhoto.setCaption(caption);
        return sendPhoto;
    }
}
