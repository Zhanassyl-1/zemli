CREATE TABLE IF NOT EXISTS players (
    id BIGSERIAL PRIMARY KEY,
    telegram_id BIGINT NOT NULL UNIQUE,
    village_name TEXT NOT NULL,
    faction TEXT NOT NULL,
    city_level INTEGER NOT NULL DEFAULT 1,
    morale INTEGER NOT NULL DEFAULT 100,
    builders_count INTEGER NOT NULL DEFAULT 1,
    has_cannon INTEGER NOT NULL DEFAULT 0,
    has_armor INTEGER NOT NULL DEFAULT 0,
    has_crossbow INTEGER NOT NULL DEFAULT 0,
    equipped_armor TEXT DEFAULT NULL,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS resources (
    player_id BIGINT PRIMARY KEY,
    wood INTEGER NOT NULL DEFAULT 0,
    stone INTEGER NOT NULL DEFAULT 0,
    food INTEGER NOT NULL DEFAULT 0,
    iron INTEGER NOT NULL DEFAULT 0,
    gold INTEGER NOT NULL DEFAULT 0,
    mana INTEGER NOT NULL DEFAULT 0,
    alcohol INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS buildings (
    player_id BIGINT NOT NULL,
    building_type TEXT NOT NULL,
    level INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (player_id, building_type),
    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS army (
    player_id BIGINT NOT NULL,
    unit_type TEXT NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (player_id, unit_type),
    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS inventory (
    player_id BIGINT NOT NULL,
    item_type TEXT NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (player_id, item_type),
    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS alliances (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    leader_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (leader_id) REFERENCES players(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS alliance_members (
    id BIGSERIAL PRIMARY KEY,
    alliance_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL UNIQUE,
    joined_at BIGINT NOT NULL,
    FOREIGN KEY (alliance_id) REFERENCES alliances(id) ON DELETE CASCADE,
    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS alliance_invites (
    id BIGSERIAL PRIMARY KEY,
    alliance_id BIGINT NOT NULL,
    inviter_id BIGINT NOT NULL,
    invited_player_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (alliance_id) REFERENCES alliances(id) ON DELETE CASCADE,
    FOREIGN KEY (inviter_id) REFERENCES players(id) ON DELETE CASCADE,
    FOREIGN KEY (invited_player_id) REFERENCES players(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS vassals (
    vassal_id BIGINT PRIMARY KEY,
    lord_id BIGINT NOT NULL,
    FOREIGN KEY (vassal_id) REFERENCES players(id) ON DELETE CASCADE,
    FOREIGN KEY (lord_id) REFERENCES players(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS market_listings (
    id BIGSERIAL PRIMARY KEY,
    seller_id BIGINT NOT NULL,
    item_type TEXT NOT NULL,
    price INTEGER NOT NULL,
    is_auction INTEGER NOT NULL DEFAULT 0,
    auction_ends_at BIGINT,
    highest_bidder_id BIGINT,
    highest_bid INTEGER,
    FOREIGN KEY (seller_id) REFERENCES players(id) ON DELETE CASCADE,
    FOREIGN KEY (highest_bidder_id) REFERENCES players(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS trade_offers (
    id BIGSERIAL PRIMARY KEY,
    seller_id BIGINT NOT NULL,
    give_resource TEXT NOT NULL,
    give_amount INTEGER NOT NULL,
    want_resource TEXT NOT NULL,
    want_amount INTEGER NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    status TEXT NOT NULL,
    FOREIGN KEY (seller_id) REFERENCES players(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS battle_log (
    id BIGSERIAL PRIMARY KEY,
    attacker_id BIGINT NOT NULL,
    defender_id BIGINT NOT NULL,
    attacker_power INTEGER NOT NULL,
    defender_power INTEGER NOT NULL,
    winner_id BIGINT NOT NULL,
    resources_stolen INTEGER NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (attacker_id) REFERENCES players(id) ON DELETE CASCADE,
    FOREIGN KEY (defender_id) REFERENCES players(id) ON DELETE CASCADE,
    FOREIGN KEY (winner_id) REFERENCES players(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS daily_log (
    id BIGSERIAL PRIMARY KEY,
    event_type TEXT NOT NULL,
    description TEXT NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS player_state (
    player_id BIGINT NOT NULL,
    state_key TEXT NOT NULL,
    state_value BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (player_id, state_key),
    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS battles (
    id BIGSERIAL PRIMARY KEY,
    attacker_id BIGINT NOT NULL,
    defender_id BIGINT NOT NULL,
    attacker_hp INTEGER NOT NULL,
    defender_hp INTEGER NOT NULL,
    attacker_max_hp INTEGER NOT NULL,
    defender_max_hp INTEGER NOT NULL,
    current_round INTEGER NOT NULL,
    max_rounds INTEGER NOT NULL,
    attacker_action TEXT,
    defender_action TEXT,
    status TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    round_started_at BIGINT,
    history TEXT NOT NULL DEFAULT '',
    FOREIGN KEY (attacker_id) REFERENCES players(id) ON DELETE CASCADE,
    FOREIGN KEY (defender_id) REFERENCES players(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS server_state (
    state_key TEXT PRIMARY KEY,
    state_value TEXT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_players_telegram_id ON players(telegram_id);
CREATE INDEX IF NOT EXISTS idx_players_telegram ON players(telegram_id);
CREATE INDEX IF NOT EXISTS idx_army_player ON army(player_id);
CREATE INDEX IF NOT EXISTS idx_resources_player ON resources(player_id);
CREATE INDEX IF NOT EXISTS idx_buildings_player ON buildings(player_id);
CREATE INDEX IF NOT EXISTS idx_inventory_player ON inventory(player_id);
CREATE INDEX IF NOT EXISTS idx_battles_attacker ON battles(attacker_id);
CREATE INDEX IF NOT EXISTS idx_battles_defender ON battles(defender_id);
CREATE INDEX IF NOT EXISTS idx_market_auction_ends ON market_listings(auction_ends_at);
CREATE INDEX IF NOT EXISTS idx_daily_log_created ON daily_log(created_at);
CREATE INDEX IF NOT EXISTS idx_player_state_key ON player_state(state_key);
CREATE INDEX IF NOT EXISTS idx_battles_status ON battles(status);
CREATE INDEX IF NOT EXISTS idx_alliance_members_alliance ON alliance_members(alliance_id);
CREATE INDEX IF NOT EXISTS idx_alliance_invites_player ON alliance_invites(invited_player_id);
CREATE INDEX IF NOT EXISTS idx_trade_offers_status_expires ON trade_offers(status, expires_at);
