CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    owner_name VARCHAR(100) NOT NULL CHECK (length(btrim(owner_name)) > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('AUD', 'USD', 'EUR', 'GBP')),
    balance NUMERIC(19, 2) NOT NULL DEFAULT 0.00 CHECK (balance >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
