create table messages (
    id char(26) primary key,
    conversation_id char(26) not null,
    sender_user_id char(26) not null,
    message_type varchar(24) not null,
    body varchar(2000) not null,
    moderation_state varchar(24) not null,
    created_at timestamp(6) not null,
    constraint fk_messages_conversation
        foreign key (conversation_id) references conversations (id),
    constraint fk_messages_sender_participant
        foreign key (conversation_id, sender_user_id) references conversation_participants (conversation_id, user_id),
    constraint chk_messages_type
        check (message_type in ('TEXT')),
    constraint chk_messages_moderation_state
        check (moderation_state in ('VISIBLE')),
    constraint chk_messages_body_not_blank
        check (char_length(trim(body)) > 0 and char_length(body) <= 2000)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create index idx_messages_conversation_created
    on messages (conversation_id, created_at, id);

create index idx_messages_sender_created
    on messages (sender_user_id, created_at, id);
