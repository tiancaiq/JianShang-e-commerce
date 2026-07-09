alter table listing_trade_completions
    add column quantity_sold int not null default 1 after buyer_user_id;

alter table listing_trade_completions
    add constraint chk_listing_trade_completions_quantity_sold check (quantity_sold >= 1);
