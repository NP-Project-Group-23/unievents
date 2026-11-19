package com.unievents.unievents_backend.event;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MigrationTests {
    // Keep migration connections alive like the application pool. H2 check expressions
    // can retain their creating session after a direct Flyway connection is closed.
    private final java.util.Map<String,com.zaxxer.hikari.HikariDataSource> pools = new java.util.HashMap<>();
    @org.junit.jupiter.api.AfterEach
    void closePools() { pools.values().forEach(com.zaxxer.hikari.HikariDataSource::close); }
    private String url() { return "jdbc:h2:mem:migration-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"; }
    private Flyway flyway(String url, String target) {
        var source = pools.computeIfAbsent(url, key -> {
            var pool = new com.zaxxer.hikari.HikariDataSource();
            pool.setJdbcUrl(key); pool.setUsername("sa"); pool.setPassword("");
            pool.setMaximumPoolSize(3);
            return pool;
        });
        return Flyway.configure().dataSource(source).locations("classpath:db/migration").target(target).load();
    }

    @Test
    void freshInstallAndRepeatAreVersioned() {
        var migration = flyway(url(),"latest");
        assertEquals(4,migration.migrate().migrationsExecuted);
        assertEquals(0,migration.migrate().migrationsExecuted);
        migration.validate();
    }

    @Test
    void upgradePreservesUsersAndEmptyInventory() throws Exception {
        String url = url();
        flyway(url,"1").migrate();
        try (var connection = DriverManager.getConnection(url,"sa",""); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_users(name,email,password_hash) VALUES ('Existing','existing@test.com','hash')");
        }
        assertEquals(2,flyway(url,"latest").migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url,"sa",""); var sql = connection.createStatement();
             var result = sql.executeQuery("SELECT email FROM app_users")) {
            assertTrue(result.next()); assertEquals("existing@test.com",result.getString(1));
        }
    }

    @Test
    void orphanInventoryBlocksForeignKeysInsteadOfDeletingData() throws Exception {
        String url = url();
        flyway(url,"2").migrate();
        try (var connection = DriverManager.getConnection(url,"sa",""); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO seats(event_id,seat_number,status) VALUES (999,'A1','AVAILABLE')");
        }
        assertThrows(org.flywaydb.core.api.FlywayException.class,() -> flyway(url,"latest").migrate());
        try (var connection = DriverManager.getConnection(url,"sa",""); var sql = connection.createStatement();
             var result = sql.executeQuery("SELECT COUNT(*) FROM seats WHERE event_id=999")) {
            assertTrue(result.next()); assertEquals(1,result.getInt(1));
        }
    }
}
