CREATE TABLE IF NOT EXISTS players (
    id BIGSERIAL PRIMARY KEY,
    telegram_id BIGINT NOT NULL UNIQUE,
    village_name TEXT NOT NULL,
    faction TEXT NOT NULL,
    city_level INTEGER NOT NULL DEFAULT 1,
    morale INTEGER NOT NULL DEFAULT 100 CHECK (morale >= 0 AND morale <= 100),
    builders_count INTEGER NOT NULL DEFAULT 1 CHECK (builders_count >= 0),
    has_cannon INTEGER NOT NULL DEFAULT 0 CHECK (has_cannon IN (0, 1)),
    has_armor INTEGER NOT NULL DEFAULT 0 CHECK (has_armor IN (0, 1)),
    has_crossbow INTEGER NOT NULL DEFAULT 0 CHECK (has_crossbow IN (0, 1)),
    equipped_armor TEXT,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS resources (
    player_id BIGINT PRIMARY KEY REFERENCES players(id) ON DELETE CASCADE,
    wood INTEGER NOT NULL DEFAULT 0 CHECK (wood >= 0),
    stone INTEGER NOT NULL DEFAULT 0 CHECK (stone >= 0),
    food INTEGER NOT NULL DEFAULT 0 CHECK (food >= 0),
    iron INTEGER NOT NULL DEFAULT 0 CHECK (iron >= 0),
    gold INTEGER NOT NULL DEFAULT 0 CHECK (gold >= 0),
    mana INTEGER NOT NULL DEFAULT 0 CHECK (mana >= 0),
    alcohol INTEGER NOT NULL DEFAULT 0 CHECK (alcohol >= 0),
    last_updated TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS buildings (
    player_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    building_type TEXT NOT NULL,
    level INTEGER NOT NULL DEFAULT 1 CHECK (level >= 0),
    PRIMARY KEY (player_id, building_type)
);

CREATE TABLE IF NOT EXISTS army (
    player_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    unit_type TEXT NOT NULL,
    unit_power INTEGER NOT NULL DEFAULT 0 CHECK (unit_power >= 0),
    quantity INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    PRIMARY KEY (player_id, unit_type)
);

CREATE TABLE IF NOT EXISTS inventory (
    player_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    item_type TEXT NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    PRIMARY KEY (player_id, item_type)
);

CREATE TABLE IF NOT EXISTS alliances (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    leader_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS alliance_members (
    id BIGSERIAL PRIMARY KEY,
    alliance_id BIGINT NOT NULL REFERENCES alliances(id) ON DELETE CASCADE,
    player_id BIGINT NOT NULL UNIQUE REFERENCES players(id) ON DELETE CASCADE,
    joined_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS alliance_invites (
    id BIGSERIAL PRIMARY KEY,
    alliance_id BIGINT NOT NULL REFERENCES alliances(id) ON DELETE CASCADE,
    inviter_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    invited_player_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS vassals (
    vassal_id BIGINT PRIMARY KEY REFERENCES players(id) ON DELETE CASCADE,
    lord_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS market_listings (
    id BIGSERIAL PRIMARY KEY,
    seller_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    item_type TEXT NOT NULL,
    price INTEGER NOT NULL CHECK (price >= 0),
    is_auction INTEGER NOT NULL DEFAULT 0 CHECK (is_auction IN (0, 1)),
    auction_ends_at BIGINT,
    highest_bidder_id BIGINT REFERENCES players(id) ON DELETE SET NULL,
    highest_bid INTEGER CHECK (highest_bid IS NULL OR highest_bid >= 0)
);

CREATE TABLE IF NOT EXISTS trade_offers (
    id BIGSERIAL PRIMARY KEY,
    seller_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    give_resource TEXT NOT NULL,
    give_amount INTEGER NOT NULL CHECK (give_amount > 0),
    want_resource TEXT NOT NULL,
    want_amount INTEGER NOT NULL CHECK (want_amount > 0),
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    status TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS battle_log (
    id BIGSERIAL PRIMARY KEY,
    attacker_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    defender_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    attacker_power INTEGER NOT NULL,
    defender_power INTEGER NOT NULL,
    winner_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    resources_stolen INTEGER NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS daily_log (
    id BIGSERIAL PRIMARY KEY,
    event_type TEXT NOT NULL,
    description TEXT NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS player_state (
    player_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    state_key TEXT NOT NULL,
    state_value BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (player_id, state_key)
);

CREATE TABLE IF NOT EXISTS battles (
    id BIGSERIAL PRIMARY KEY,
    attacker_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    defender_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
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
    history TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS server_state (
    state_key TEXT PRIMARY KEY,
    state_value TEXT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS map_buildings (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    building_type TEXT NOT NULL,
    built_at BIGINT NOT NULL,
    UNIQUE(owner_id, x, y)
);

CREATE TABLE IF NOT EXISTS kingdom (
    player_id BIGINT PRIMARY KEY REFERENCES players(id) ON DELETE CASCADE,
    race VARCHAR(50) NOT NULL,
    home_x INTEGER NOT NULL DEFAULT 0,
    home_y INTEGER NOT NULL DEFAULT 0,
    level INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_players_telegram_id ON players(telegram_id);
CREATE INDEX IF NOT EXISTS idx_army_player ON army(player_id);
CREATE INDEX IF NOT EXISTS idx_resources_player ON resources(player_id);
CREATE INDEX IF NOT EXISTS idx_buildings_player ON buildings(player_id);
CREATE INDEX IF NOT EXISTS idx_inventory_player ON inventory(player_id);
CREATE INDEX IF NOT EXISTS idx_battles_attacker ON battles(attacker_id);
CREATE INDEX IF NOT EXISTS idx_battles_defender ON battles(defender_id);
CREATE INDEX IF NOT EXISTS idx_battles_status ON battles(status);
CREATE INDEX IF NOT EXISTS idx_market_auction_ends ON market_listings(auction_ends_at);
CREATE INDEX IF NOT EXISTS idx_daily_log_created ON daily_log(created_at);
CREATE INDEX IF NOT EXISTS idx_player_state_key ON player_state(state_key);
CREATE INDEX IF NOT EXISTS idx_alliance_members_alliance ON alliance_members(alliance_id);
CREATE INDEX IF NOT EXISTS idx_alliance_invites_player ON alliance_invites(invited_player_id);
CREATE INDEX IF NOT EXISTS idx_trade_offers_status_expires ON trade_offers(status, expires_at);
CREATE INDEX IF NOT EXISTS idx_map_buildings_area ON map_buildings(x, y);
