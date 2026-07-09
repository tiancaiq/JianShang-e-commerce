create table listing_trade_completions (
    id char(26) primary key,
    listing_id char(26) not null,
    conversation_id char(26) not null,
    seller_user_id char(26) not null,
    buyer_user_id char(26) not null,
    status varchar(32) not null,
    seller_marked_done_at timestamp(6) null,
    buyer_confirmed_at timestamp(6) null,
    cancelled_at timestamp(6) null,
    version bigint not null default 0,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_listing_trade_completions_conversation
        foreign key (conversation_id) references conversations (id),
    constraint uq_listing_trade_completions_conversation
        unique (conversation_id),
    constraint chk_listing_trade_completions_status
        check (status in ('SELLER_MARKED_DONE', 'BUYER_CONFIRMED', 'CANCELLED')),
    constraint chk_listing_trade_completions_no_self
        check (seller_user_id <> buyer_user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create index idx_listing_trade_completions_listing
    on listing_trade_completions (listing_id, status, updated_at);

create index idx_listing_trade_completions_seller
    on listing_trade_completions (seller_user_id, updated_at);

create index idx_listing_trade_completions_buyer
    on listing_trade_completions (buyer_user_id, updated_at);
