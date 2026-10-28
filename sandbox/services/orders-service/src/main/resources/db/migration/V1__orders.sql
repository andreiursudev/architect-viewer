create table orders (
    id          uuid primary key,
    customer_id text        not null,
    sku         text        not null,
    quantity    int         not null,
    status      text        not null,
    created_at  timestamptz not null
);
