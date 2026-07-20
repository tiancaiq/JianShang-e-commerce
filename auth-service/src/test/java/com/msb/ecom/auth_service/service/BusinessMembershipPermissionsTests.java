package com.msb.ecom.auth_service.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessMembershipPermissionsTests {

    @Test
    void ownerReceivesOrderAndFinanceReadPermissions() {
        assertThat(BusinessMembershipService.permissionsFor("OWNER"))
                .containsExactly(
                        "LISTING_DRAFT_CREATE",
                        "INVENTORY_VIEW",
                        "INVENTORY_MANAGE",
                        "ORDER_VIEW",
                        "ORDER_FINANCE_VIEW");
    }

    @Test
    void managerReceivesOrderReadWithoutFinanceRead() {
        assertThat(BusinessMembershipService.permissionsFor("MANAGER"))
                .containsExactly(
                        "LISTING_DRAFT_CREATE",
                        "INVENTORY_VIEW",
                        "INVENTORY_MANAGE",
                        "ORDER_VIEW");
    }

    @Test
    void unknownRolesReceiveNoPermissions() {
        assertThat(BusinessMembershipService.permissionsFor("STAFF")).isEmpty();
    }
}
