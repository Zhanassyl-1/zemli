package com.zemli.bot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class ImageService {
    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    private final Map<String, String> victoryImages = new HashMap<>();
    private final Map<String, String> defeatImages = new HashMap<>();
    private final Map<String, String> factionPreviewImages = new HashMap<>();

    public ImageService() {
        initVictoryImages();
        initDefeatImages();
        initFactionPreviewImages();
    }

    private void initVictoryImages() {
        // Ацтеки
        putVictory("aztec", "knight", "/images/heroes/lucid-origin_pixel_art_16-bit_aztec_eagle_warrior_holding_crusader_head_dead_knight_broken_sw-0.jpg");
        putVictory("aztec", "samurai", "/images/heroes/lucid-origin_pixel_art_16-bit_aztec_jaguar_warrior_holding_samurai_head_dead_samurai_broken_k-0.jpg");
        putVictory("aztec", "viking", "/images/heroes/lucid-origin_pixel_art_16-bit_aztec_warrior_holding_viking_head_dead_berserker_broken_axe_jun-0.jpg");
        putVictory("aztec", "mongol", "/images/heroes/lucid-origin_pixel_art_16-bit_aztec_warrior_holding_mongol_head_dead_horse_archer_broken_bow_-0.jpg");
        putVictory("aztec", "arabian", "/images/heroes/lucid-origin_pixel_art_16-bit_aztec_warrior_holding_arabian_head_dead_desert_warrior_broken_s-0.jpg");

        // Пустынники
        putVictory("arabian", "knight", "/images/heroes/lucid-origin_pixel_art_16-bit_arabian_warrior_holding_crusader_head_dead_knight_broken_sword_-0.jpg");
        putVictory("arabian", "samurai", "/images/heroes/lucid-origin_pixel_art_16-bit_arabian_warrior_holding_samurai_head_dead_samurai_broken_katana-0.jpg");
        putVictory("arabian", "viking", "/images/heroes/lucid-origin_pixel_art_16-bit_arabian_warrior_holding_viking_head_dead_berserker_broken_axe_d-0.jpg");
        putVictory("arabian", "mongol", "/images/heroes/lucid-origin_pixel_art_16-bit_arabian_warrior_holding_mongol_head_dead_horse_archer_broken_bo-0.jpg");
        putVictory("arabian", "aztec", "/images/heroes/lucid-origin_pixel_art_16-bit_arabian_warrior_holding_aztec_head_dead_jaguar_warrior_pyramid_-0.jpg");

        // Рыцари
        putVictory("knight", "aztec", "/images/heroes/lucid-origin_pixel_art_16-bit_conquistador_knight_holding_aztec_feather_crown_dead_jaguar_war-0.jpg");
        putVictory("knight", "mongol", "/images/heroes/lucid-origin_pixel_art_16-bit_knight_holding_mongol_horse_head_dead_mongol_archer_on_ground_b-0.jpg");
        putVictory("knight", "viking", "/images/heroes/lucid-origin_pixel_art_16-bit_knight_standing_over_dead_viking_body_holding_viking_helmet_as_-0.jpg");
        putVictory("knight", "samurai", "/images/heroes/lucid-origin_pixel_art_16-bit_knight_holding_severed_samurai_head_blood_dripping_dead_samurai-0.jpg");
        putVictory("knight", "arabian", "/images/heroes/lucid-origin_pixel_art_16-bit_knight_holding_saracen_banner_trophy_dead_arabian_warrior_at_fe-0.jpg");

        // Самураи
        putVictory("samurai", "knight", "/images/heroes/lucid-origin_pixel_art_16-bit_samurai_holding_severed_knight_head_dead_knight_in_armor_at_fee-0.jpg");
        putVictory("samurai", "viking", "/images/heroes/lucid-origin_pixel_art_16-bit_samurai_holding_viking_head_by_hair_dead_berserker_body_broken_-0.jpg");
        putVictory("samurai", "mongol", "/images/heroes/lucid-origin_pixel_art_16-bit_samurai_standing_over_dead_mongol_horse_archer_holding_mongol_b-0.jpg");
        putVictory("samurai", "arabian", "/images/heroes/lucid-origin_pixel_art_16-bit_samurai_holding_arabian_head_dead_desert_warrior_broken_scimita-0.jpg");
        putVictory("samurai", "aztec", "/images/heroes/lucid-origin_pixel_art_16-bit_samurai_holding_aztec_jaguar_helmet_trophy_dead_jaguar_warrior_-0.jpg");

        // Викинги
        putVictory("viking", "knight", "/images/heroes/lucid-origin_pixel_art_16-bit_viking_holding_knight_helmet_with_head_inside_dead_knight_in_ar-0.jpg");
        putVictory("viking", "samurai", "/images/heroes/lucid-origin_pixel_art_16-bit_viking_holding_samurai_head_dead_samurai_body_broken_katana_nor-0.jpg");
        putVictory("viking", "mongol", "/images/heroes/lucid-origin_pixel_art_16-bit_viking_holding_mongol_head_dead_horse_archer_broken_bow_frozen_-0.jpg");
        putVictory("viking", "arabian", "/images/heroes/lucid-origin_pixel_art_16-bit_viking_holding_arabian_head_dead_desert_warrior_oasis_turned_re-0.jpg");
        putVictory("viking", "aztec", "/images/heroes/lucid-origin_pixel_art_16-bit_viking_holding_aztec_priest_head_dead_jaguar_warrior_pyramid_ba-0.jpg");

        // Монголы
        putVictory("mongol", "knight", "/images/heroes/lucid-origin_pixel_art_16-bit_mongol_warrior_holding_knight_helmet_with_head_dead_knight_brok-0.jpg");
        putVictory("mongol", "samurai", "/images/heroes/lucid-origin_pixel_art_16-bit_mongol_holding_samurai_head_dead_samurai_broken_katana_steppe_w-0.jpg");
        putVictory("mongol", "viking", "/images/heroes/lucid-origin_pixel_art_16-bit_mongol_holding_viking_head_dead_berserker_broken_axe_frozen_ste-0.jpg");
        putVictory("mongol", "arabian", "/images/heroes/lucid-origin_pixel_art_16-bit_mongol_holding_arabian_head_dead_desert_warrior_broken_scimitar-0.jpg");
        putVictory("mongol", "aztec", "/images/heroes/lucid-origin_pixel_art_16-bit_mongol_holding_aztec_head_dead_jaguar_warrior_pyramid_in_distan-0.jpg");

        // Универсальная победа
        victoryImages.put("universal", "/images/heroes/lucid-origin_pixel_art_16-bit_victorious_warrior_holding_severed_enemy_head_dead_bodies_aroun-0.jpg");
    }

    private void initDefeatImages() {
        // Временный режим: используем универсальную победу/текст до появления отдельных defeat-артов.
        defeatImages.put("universal", "/images/heroes/lucid-origin_pixel_art_16-bit_victorious_warrior_holding_severed_enemy_head_dead_bodies_aroun-0.jpg");
    }

    private void initFactionPreviewImages() {
        factionPreviewImages.put("knight", "/images/heroes/lucid-origin_pixel_art_medieval_knight_full_plate_armor_sword_and_shield_silver_blue_colors_1-0.jpg");
        factionPreviewImages.put("samurai", "/images/heroes/lucid-origin_pixel_art_samurai_warrior_japanese_armor_katana_red_black_colors_128x128_sprite_-0.jpg");
        factionPreviewImages.put("viking", "/images/heroes/lucid-origin_pixel_art_viking_warrior_fur_armor_axe_round_shield_blue_grey_colors_128x128_spr-0.jpg");
        factionPreviewImages.put("mongol", "/images/heroes/lucid-origin_pixel_art_mongol_warrior_leather_armor_bow_on_horseback_brown_gold_colors_128x12-0.jpg");
        factionPreviewImages.put("arabian", "/images/heroes/lucid-origin_pixel_art_desert_nomad_warrior_robes_turban_scimitar_sand_orange_colors_128x128_-0.jpg");
        factionPreviewImages.put("aztec", "/images/heroes/lucid-origin_pixel_art_aztec_warrior_feathered_headdress_obsidian_club_green_gold_colors_128x-0.jpg");
    }

    public SendPhoto getVictoryImage(String winner, String loser, String chatId) {
        String key = normalizeRace(winner) + "_vs_" + normalizeRace(loser) + "_win";
        String imagePath = victoryImages.getOrDefault(key, victoryImages.get("universal"));
        return buildSendPhoto(chatId, imagePath, "🏆 ПОБЕДА!");
    }

    public SendPhoto getDefeatImage(String race, String chatId) {
        String key = normalizeRace(race) + "_defeat";
        String imagePath = defeatImages.getOrDefault(key, defeatImages.get("universal"));
        if (!defeatImages.containsKey(key)) {
            log.warn("Нет отдельной defeat-картинки для {}. Использую универсальную.", key);
        }
        return buildSendPhoto(chatId, imagePath, "💔 ВАША АРМИЯ РАЗГРОМЛЕНА...");
    }

    public SendPhoto getUniversalVictory(String chatId) {
        return buildSendPhoto(chatId, victoryImages.get("universal"), "🏆 ПОБЕДА!");
    }

    public SendPhoto getUniversalDefeat(String chatId) {
        return buildSendPhoto(chatId, defeatImages.get("universal"), "💔 ПОРАЖЕНИЕ...");
    }

    public SendPhoto getArtifactImage(String chatId, String artifactName) {
        return buildSendPhoto(chatId, victoryImages.get("universal"), "💎 Найден артефакт: " + artifactName);
    }

    public SendPhoto getFactionPreviewImage(String race, String chatId, String caption) {
        String imagePath = factionPreviewImages.get(normalizeRace(race));
        if (imagePath == null) {
            return null;
        }
        return buildSendPhoto(chatId, imagePath, caption);
    }

    private SendPhoto buildSendPhoto(String chatId, String imagePath, String caption) {
        String normalizedPath = imagePath == null ? "" : (imagePath.startsWith("/") ? imagePath.substring(1) : imagePath);
        ClassLoader classLoader = getClass().getClassLoader();
        try (InputStream imageStream = classLoader.getResourceAsStream(normalizedPath)) {
            if (imageStream == null) {
                log.error("❌ КАРТИНКА НЕ НАЙДЕНА: {}", imagePath);
                return null;
            }
            byte[] bytes = imageStream.readAllBytes();
            if (bytes.length == 0) {
                log.error("❌ КАРТИНКА ПУСТАЯ: {}", imagePath);
                return null;
            }
            String fileName = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);
            InputFile inputFile = new InputFile(new ByteArrayInputStream(bytes), fileName);
            SendPhoto sendPhoto = new SendPhoto();
            sendPhoto.setChatId(chatId);
            sendPhoto.setPhoto(inputFile);
            sendPhoto.setCaption(caption);
            return sendPhoto;
        } catch (Exception e) {
            log.error("❌ Ошибка загрузки картинки {}: {}", imagePath, e.getMessage(), e);
            return null;
        }
    }

    private String normalizeRace(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT).trim();
        return switch (s) {
            case "knights", "knight", "рыцари", "рыцарь" -> "knight";
            case "samurais", "samurai", "самураи", "самурай" -> "samurai";
            case "vikings", "viking", "викинги", "викинг" -> "viking";
            case "mongols", "mongol", "монголы", "монгол" -> "mongol";
            case "arabians", "arabian", "desert_dwellers", "пустынники", "пустынник" -> "arabian";
            case "aztecs", "aztec", "ацтеки", "ацтек" -> "aztec";
            default -> s;
        };
    }

    public SendPhoto testImage(String chatId) {
        return getVictoryImage("AZTEC", "KNIGHT", chatId);
    }

    private void putVictory(String winner, String loser, String path) {
        victoryImages.put(winner + "_vs_" + loser + "_win", path);
    }
}
