CREATE TABLE IF NOT EXISTS users (
 id VARCHAR(50) PRIMARY KEY, name VARCHAR(80) NOT NULL, alias VARCHAR(30) UNIQUE NOT NULL,
 phone VARCHAR(9) UNIQUE, role VARCHAR(12) NOT NULL, status VARCHAR(12) NOT NULL,
 created_at VARCHAR(35) NOT NULL, completed_at VARCHAR(35), campaign VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
 token_hash VARCHAR(64) PRIMARY KEY, user_id VARCHAR(50) NOT NULL REFERENCES users(id), expires_at VARCHAR(35) NOT NULL
);
CREATE TABLE IF NOT EXISTS payments (
 id VARCHAR(50) PRIMARY KEY, payer_id VARCHAR(50) NOT NULL REFERENCES users(id),
 destination_id VARCHAR(50) NOT NULL REFERENCES users(id), amount BIGINT NOT NULL CHECK(amount>0),
 channel VARCHAR(12) NOT NULL, scenario VARCHAR(12) NOT NULL, status VARCHAR(12) NOT NULL,
 reason VARCHAR(40), idem_key VARCHAR(100) NOT NULL, fingerprint VARCHAR(300) NOT NULL,
 created_at VARCHAR(35) NOT NULL, updated_at VARCHAR(35) NOT NULL, reversed BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE(payer_id,idem_key)
);
CREATE TABLE IF NOT EXISTS ledger (
 id VARCHAR(50) PRIMARY KEY, payment_id VARCHAR(50), account VARCHAR(60) NOT NULL,
 amount BIGINT NOT NULL, phase VARCHAR(20) NOT NULL, created_at VARCHAR(35) NOT NULL,
 UNIQUE(payment_id,account,phase)
);
CREATE TABLE IF NOT EXISTS provider_results (
 payment_id VARCHAR(50) PRIMARY KEY REFERENCES payments(id), channel VARCHAR(12) NOT NULL,
 status VARCHAR(12) NOT NULL, amount BIGINT NOT NULL, updated_at VARCHAR(35) NOT NULL
);
CREATE TABLE IF NOT EXISTS callbacks (
 event_id VARCHAR(100) PRIMARY KEY, payment_id VARCHAR(50) NOT NULL REFERENCES payments(id),
 fingerprint VARCHAR(150) NOT NULL, created_at VARCHAR(35) NOT NULL
);
CREATE TABLE IF NOT EXISTS audit (
 id VARCHAR(50) PRIMARY KEY, actor VARCHAR(50) NOT NULL, action VARCHAR(50) NOT NULL,
 reference_id VARCHAR(100), request_id VARCHAR(50) NOT NULL, correlation_id VARCHAR(50) NOT NULL,
 detail VARCHAR(300) NOT NULL, created_at VARCHAR(35) NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
 id VARCHAR(50) PRIMARY KEY, user_id VARCHAR(50) NOT NULL REFERENCES users(id),
 name VARCHAR(40) NOT NULL, reference_id VARCHAR(50), created_at VARCHAR(35) NOT NULL
);
CREATE TABLE IF NOT EXISTS qr_codes (
 id VARCHAR(50) PRIMARY KEY, merchant_id VARCHAR(50) NOT NULL REFERENCES users(id),
 amount BIGINT NOT NULL CHECK(amount>0), expires_at VARCHAR(35) NOT NULL
);
CREATE TABLE IF NOT EXISTS cases (
 id VARCHAR(50) PRIMARY KEY, payment_id VARCHAR(50) NOT NULL REFERENCES payments(id),
 category VARCHAR(20) NOT NULL, note VARCHAR(400) NOT NULL, status VARCHAR(20) NOT NULL,
 resolution VARCHAR(400), created_at VARCHAR(35) NOT NULL, updated_at VARCHAR(35) NOT NULL
);
CREATE TABLE IF NOT EXISTS mismatches (
 id VARCHAR(50) PRIMARY KEY, payment_id VARCHAR(50) NOT NULL REFERENCES payments(id),
 kind VARCHAR(40) NOT NULL, status VARCHAR(20) NOT NULL, created_at VARCHAR(35) NOT NULL,
 updated_at VARCHAR(35) NOT NULL, UNIQUE(payment_id,kind)
);
CREATE TABLE IF NOT EXISTS acquisition (
 month_key VARCHAR(7) NOT NULL, campaign VARCHAR(40) NOT NULL, cents BIGINT NOT NULL CHECK(cents>=0),
 PRIMARY KEY(month_key,campaign)
);
CREATE INDEX IF NOT EXISTS payments_created ON payments(created_at);
CREATE INDEX IF NOT EXISTS ledger_account ON ledger(account);
CREATE INDEX IF NOT EXISTS events_created ON events(created_at,name);
