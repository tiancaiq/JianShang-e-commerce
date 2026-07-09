create table conversations (
    id char(26) primary key,
    conversation_type varchar(40) not null,
    subject_listing_id char(26) not null,
    buyer_user_id char(26) not null,
    seller_user_id char(26) not null,
    status varchar(24) not null,
    last_message_id char(26) null,
    last_message_at timestamp(6) null,
    version bigint not null default 0,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint uq_listing_buyer_seller_conversation
        unique (conversation_type, subject_listing_id, buyer_user_id, seller_user_id),
    constraint chk_conversations_type
        check (conversation_type in ('LISTING_BUYER_SELLER')),
    constraint chk_conversations_status
        check (status in ('OPEN', 'ARCHIVED', 'LOCKED')),
    constraint chk_conversations_no_self_chat
        check (buyer_user_id <> seller_user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create index idx_conversations_buyer_updated
    on conversations (buyer_user_id, updated_at, id);

create index idx_conversations_seller_updated
    on conversations (seller_user_id, updated_at, id);

create table conversation_participants (
    conversation_id char(26) not null,
    user_id char(26) not null,
    role_in_conversation varchar(24) not null,
    last_read_message_id char(26) null,
    last_read_at timestamp(6) null,
    joined_at timestamp(6) not null,
    primary key (conversation_id, user_id),
    constraint fk_conversation_participants_conversation
        foreign key (conversation_id) references conversations (id),
    constraint chk_conversation_participants_role
        check (role_in_conversation in ('BUYER', 'SELLER'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create index idx_conversation_participants_user
    on conversation_participants (user_id, conversation_id);
