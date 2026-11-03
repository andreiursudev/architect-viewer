create table shipments (
    order_id   uuid primary key,
    status     varchar(20) not null,
    created_at timestamptz not null
);
