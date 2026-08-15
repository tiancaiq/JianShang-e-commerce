UPDATE admin_permissions
SET reserved = FALSE,
    description = CASE id
        WHEN 'admin.listing.suspend' THEN 'Restrict or temporarily suspend listing marketplace capabilities'
        WHEN 'admin.listing.reinstate' THEN 'Revoke one listing enforcement action'
        ELSE description
    END
WHERE id IN ('admin.listing.suspend', 'admin.listing.reinstate');
