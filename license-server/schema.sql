-- JobPilot 卡密服务端数据库结构（D1 / SQLite）

CREATE TABLE IF NOT EXISTS cards (
  card_key       TEXT PRIMARY KEY,
  batch          TEXT NOT NULL,                 -- 批次备注，便于售后定位某一批卡
  type           TEXT NOT NULL,                 -- time=时长卡 | quota=次数卡 | trial=试用卡
  duration_days  INTEGER,                       -- time/trial：激活后有效天数
  quota_total    INTEGER,                       -- quota：总次数
  quota_used     INTEGER NOT NULL DEFAULT 0,
  max_devices    INTEGER NOT NULL DEFAULT 1,    -- 可绑定设备数
  status         TEXT NOT NULL DEFAULT 'active',-- active | disabled
  activated_at   TEXT,                          -- 首次激活时间（激活即计时）
  expires_at     TEXT,                          -- 到期时间
  unbind_count   INTEGER NOT NULL DEFAULT 0,    -- 已用换绑次数（终身 3 次）
  last_unbind_at TEXT,
  created_at     TEXT NOT NULL,
  note           TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_cards_batch  ON cards(batch);
CREATE INDEX IF NOT EXISTS idx_cards_status ON cards(status);
CREATE INDEX IF NOT EXISTS idx_cards_type   ON cards(type);

CREATE TABLE IF NOT EXISTS activations (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  card_key    TEXT NOT NULL,
  device_id   TEXT NOT NULL,                    -- 客户端设备指纹
  device_name TEXT NOT NULL DEFAULT '',
  token       TEXT NOT NULL UNIQUE,             -- 激活令牌，心跳时携带
  created_at  TEXT NOT NULL,
  last_verify TEXT,
  UNIQUE (card_key, device_id)
);
CREATE INDEX IF NOT EXISTS idx_activations_device ON activations(device_id);
CREATE INDEX IF NOT EXISTS idx_activations_token  ON activations(token);

-- 平台自己的配置，目前只有 AI 中转这一段。value 是整段 JSON，
-- 以后加配置项不用再改表结构。
CREATE TABLE IF NOT EXISTS settings (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
