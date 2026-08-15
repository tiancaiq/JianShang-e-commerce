package com.msb.ecom.product_service.search.embedding;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ListingDiscoveryEmbeddingReceiptMigrationTests {

    @Test
    void forwardMigrationStoresOnlyBoundedDerivedReceiptFields() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/catalog/"
                        + "V202607230200__create_listing_discovery_embedding_receipts.sql"))
                .toLowerCase();

        assertThat(migration)
                .contains(
                        "create table listing_discovery_embedding_receipts",
                        "vector_bytes varbinary(6144) not null",
                        "octet_length(vector_bytes) = 6144",
                        "foreign key (request_id)",
                        "idx_listing_discovery_embedding_receipt_rebuild")
                .doesNotContain(
                        "drop table",
                        "truncate ",
                        "prompt",
                        "provider_response",
                        "seller_id",
                        "actor_user_id",
                        "email",
                        "phone",
                        "location",
                        "storage_key");
        assertThat(migration)
                .doesNotMatch("(?s).*\\n\\s+embedding_text\\s+[a-z].*");
    }
}
