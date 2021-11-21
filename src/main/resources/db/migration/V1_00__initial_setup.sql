create table if not exists business_day (
    id smallint not null auto_increment primary key,
    `reference` date not null,
    `year_and_month` int generated always as (year(reference) * 100 + month(reference)),
    `year` smallint generated always as (year(reference)),
    unique index idx_year_month_day_bd(reference desc),
    index idx_year_and_month_dr(year_and_month desc),
    index idx_year_bd(`year` desc)
);

create table if not exists device_reading (
    id int not null auto_increment primary key,
    `value` decimal(10,2) not null,
    device_type int not null,
    created_on_time time not null,
    business_day_id smallint not null,
    index idx_business_day_id_dr(business_day_id),
    constraint fk_business_day_dr foreign key (business_day_id) references business_day(id) on delete cascade on update cascade

);

create table if not exists reference_state (
    id smallint not null auto_increment primary key,
    `value` decimal(10,2) not null,
    reference_type int not null,
    created_on datetime not null default now(),
    import_reading_id int null,
    export_reading_id int null,
    business_day_id smallint not null,
    index idx_import_reading_rs(import_reading_id),
    index idx_export_reading_rs(export_reading_id),
    index idx_business_day_rs(business_day_id),
    index idx_created_date_rs(created_on desc),
    constraint fk_import_reading_rs foreign key (import_reading_id) references device_reading(id) on delete cascade on update cascade,
    constraint fk_export_reading_rs foreign key (export_reading_id) references device_reading(id) on delete cascade on update cascade,
    constraint fk_business_day_rs foreign key (business_day_id) references business_day(id) on delete cascade on update cascade

);