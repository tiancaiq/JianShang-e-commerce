# Auth-Service Migrations

Active auth-service Flyway migrations live in:

```text
classpath:db/migration/identity
```

`application.properties` pins Flyway to that location. Do not add new
auth-service migrations directly under `db/migration/`.

`V1__init_users.sql` is an archived pre-MVP tutorial migration and is not part
of the active identity schema. Leave it in place for repository history; add
new identity changes as forward-safe timestamped migrations under
`db/migration/identity/`.
