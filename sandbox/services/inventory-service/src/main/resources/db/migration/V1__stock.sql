create table stock (
    sku       text primary key,
    available int not null check (available >= 0)
);

-- sku-1 is what the scenarios order (never runs out); sku-out is always out of stock.
insert into stock (sku, available) values ('sku-1', 1000000), ('sku-out', 0);
