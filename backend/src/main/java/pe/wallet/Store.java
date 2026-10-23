package pe.wallet;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Startup
@ApplicationScoped
public class Store {
    @ConfigProperty(name = "wallet.db.url") String url;

    @FunctionalInterface
    public interface Work<T> { T run(Connection connection) throws Exception; }

    // One process owns this local store. SERIALIZABLE transactions also protect the DB constraints.
    public synchronized <T> T tx(Work<T> work) {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception error) {
                connection.rollback();
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Database transaction failed", error);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Database unavailable", error);
        }
    }

    @PostConstruct
    void init() {
        tx(c -> {
            try (var resource = Store.class.getResourceAsStream("/schema.sql")) {
                if (resource == null) throw new IllegalStateException("Missing schema");
                for (String statement : new String(resource.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                    if (!statement.isBlank()) try (Statement sql = c.createStatement()) { sql.execute(statement); }
                }
            }
            seed(c, "demo-ana", "Ana Torres", "ana.demo", "900000001", "PAYER");
            seed(c, "demo-luis", "Luis Ramos", "luis.demo", "900000002", "PAYER");
            seed(c, "demo-merchant", "Café Miraflores", "cafe.demo", "900000003", "MERCHANT");
            seed(c, "demo-ops", "Operaciones Demo", "ops.demo", "900000004", "OPS");
            return null;
        });
    }

    private void seed(Connection c, String id, String name, String alias, String phone, String role) throws SQLException {
        if (one(c, "SELECT id FROM users WHERE id=?", id) != null) return;
        String now = Instant.now().toString();
        exec(c, "INSERT INTO users VALUES(?,?,?,?,?,?,?,?,?)", id, name, alias, phone, role, "ACTIVE", now, now, "demo");
        if (!role.equals("OPS")) {
            exec(c, "INSERT INTO ledger VALUES(?,?,?,?,?,?)", UUID.randomUUID().toString(), null, id, 50000L, "FUNDING", now);
            exec(c, "INSERT INTO ledger VALUES(?,?,?,?,?,?)", UUID.randomUUID().toString(), null, "TREASURY", -50000L, "FUNDING", now);
        }
    }

    static void exec(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, parameters);
            s.executeUpdate();
        }
    }

    static List<Map<String, Object>> rows(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, parameters);
            try (ResultSet result = s.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                        row.put(result.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT), result.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    static Map<String, Object> one(Connection c, String sql, Object... parameters) throws SQLException {
        List<Map<String, Object>> result = rows(c, sql, parameters);
        return result.isEmpty() ? null : result.getFirst();
    }

    private static void bind(PreparedStatement s, Object[] parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) s.setObject(i + 1, parameters[i]);
    }
}
