update listings
set published_at = updated_at
where status = 'ACTIVE'
  and moderation_status = 'APPROVED'
  and published_at is null;
