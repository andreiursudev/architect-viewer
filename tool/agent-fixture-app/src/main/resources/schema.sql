create table if not exists items (
    id varchar(36) primary key,
    name varchar(255) not null,
    quantity int not null
);
