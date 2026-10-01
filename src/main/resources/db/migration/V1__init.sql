CREATE TABLE shows (
    id             uuid        PRIMARY KEY,
    name           text        NOT NULL,
    price_paise    bigint      NOT NULL CHECK (price_paise >= 0),
    per_user_limit int         NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    total_seats    int         NOT NULL CHECK (total_seats > 0),
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- Seat catalogue; position keeps the order the admin listed them in.
CREATE TABLE seats (
    show_id  uuid NOT NULL REFERENCES shows (id),
    seat_no  text NOT NULL,
    position int  NOT NULL,
    PRIMARY KEY (show_id, seat_no)
);

CREATE TABLE reservations (
    id              uuid        PRIMARY KEY,
    show_id         uuid        NOT NULL REFERENCES shows (id),
    user_id         text        NOT NULL,
    idempotency_key text        NOT NULL,
    seats           text[]      NOT NULL,
    amount_paise    bigint      NOT NULL CHECK (amount_paise >= 0),
    status          text        NOT NULL CHECK (status IN ('held', 'confirmed', 'expired')),
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- Exactly-once per (user, show, key); its index also serves lookups by (user_id, show_id).
    CONSTRAINT uq_reservation_idem UNIQUE (user_id, show_id, idempotency_key)
);

-- One row per held/confirmed seat. uq_seat_taken is the atomic decision:
-- at most one reservation can ever own a given (show, seat).
CREATE TABLE reservation_seats (
    reservation_id uuid NOT NULL REFERENCES reservations (id),
    show_id        uuid NOT NULL,
    seat_no        text NOT NULL,
    CONSTRAINT uq_seat_taken UNIQUE (show_id, seat_no),
    FOREIGN KEY (show_id, seat_no) REFERENCES seats (show_id, seat_no)
);

CREATE INDEX idx_reservation_seats_reservation ON reservation_seats (reservation_id);

-- Locked FOR UPDATE to serialize one user's reservations for a show (per-user limit, idempotency).
CREATE TABLE user_show (
    user_id text NOT NULL,
    show_id uuid NOT NULL REFERENCES shows (id),
    PRIMARY KEY (user_id, show_id)
);
