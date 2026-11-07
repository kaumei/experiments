package lab;

import com.zaxxer.hikari.HikariDataSource;
import io.kaumei.jdbc.tx.KaumeiTxManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

public final class KaumeiQuoteRepository implements QuoteRepository {
    private final KaumeiTxManager txManager;
    private final KaumeiQuoteStatements statements;

    public KaumeiQuoteRepository(KaumeiTxManager txManager, KaumeiQuoteStatements statements) {
        this.txManager = Objects.requireNonNull(txManager);
        this.statements = Objects.requireNonNull(statements);
    }

    public void initialize(HikariDataSource ds) {
        try (var tx = txManager.startTx()) {
            statements.createTable();
            statements.createIndex();
            tx.commit();
        }
        try (var tx = txManager.startTx()) {
            if (statements.count() == 0) {
                try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/quotes.csv"));
                     var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    loadFixture(reader);
                } catch (IOException e) {
                    throw new IllegalStateException("Could not load quote fixture", e);
                }
            }

            tx.commit();
        }

        if (operation(statements::probe) != 1) {
            throw new IllegalStateException("DB probe failed");
        }
        operation(() -> {
            statements.h2Settings().forEach(value -> System.out.println("H2 " + value));
            return null;
        });
        System.out.printf(
                "Hikari min=%d max=%d connectionTimeout=%d validationTimeout=%d idleTimeout=%d maxLifetime=%d keepaliveTime=%d initializationFailTimeout=%d%n",
                ds.getMinimumIdle(), ds.getMaximumPoolSize(), ds.getConnectionTimeout(),
                ds.getValidationTimeout(), ds.getIdleTimeout(), ds.getMaxLifetime(),
                ds.getKeepaliveTime(), ds.getInitializationFailTimeout());
    }

    void loadFixture(BufferedReader reader) throws IOException {
        reader.readLine();
        String line;
        int i = 0;
        try (var batch = statements.insertBatch()) {
            while ((line = reader.readLine()) != null) {
                var row = line.split(",");
                batch.insert(i / 3, 0, i / 3, row[0], row[1], new BigDecimal(row[2]));
                i++;
            }
            if (i != 1170) {
                throw new IllegalStateException("Wrong fixture size " + i);
            }
            batch.completeBatch();
        }
    }

    static RepositoryFailure translate(Throwable cause) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable t = cause; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                return new RepositoryFailure(
                        state != null && state.startsWith("23")
                                ? RepositoryFailure.Kind.CONFLICT : RepositoryFailure.Kind.UNEXPECTED,
                        cause);
            }
        }
        return new RepositoryFailure(RepositoryFailure.Kind.UNEXPECTED, cause);
    }

    private <T> T operation(Supplier<T> action) {
        try (var tx = txManager.startTx()) {
            T result = action.get();
            tx.commit();
            return result;
        } catch (RepositoryFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public List<Quote> latest() {
        return operation(() -> {
            var rows = new ArrayList<Quote>(QuoteBatch.SYMBOLS.size());
            for (var symbol : QuoteBatch.SYMBOLS) {
                var quotes = statements.selectQuotes(symbol, 1);
                if (!quotes.isEmpty()) {
                    rows.add(quotes.getFirst());
                }
            }
            return rows;
        });
    }

    @Override
    public List<Quote> history(String symbol, int limit) {
        QuoteBatch.validateHistory(symbol, limit);
        return operation(() -> {
            var rows = new ArrayList<>(statements.selectQuotes(symbol, limit));
            Collections.reverse(rows);
            return rows;
        });
    }

    @Override
    public void insert(QuoteBatch batch) {
        if (batch == null) throw new IllegalArgumentException("Invalid batch");
        batch.validate();
        operation(() -> {
            try (var insertSql = statements.insertBatch()) {
                for (var symbol : QuoteBatch.SYMBOLS) {
                    insertSql.insert(batch.sequence(), batch.cycle(), batch.sampleIndex(),
                            batch.marketTime(), symbol, batch.prices().get(symbol));
                }
                insertSql.completeBatch();
            }
            return null;
        });
    }

    @Override
    public QuoteState state() {
        return operation(statements::state);
    }
}
