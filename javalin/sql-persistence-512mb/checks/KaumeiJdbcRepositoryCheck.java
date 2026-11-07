package lab;

import com.zaxxer.hikari.HikariDataSource;
import io.kaumei.jdbc.runtime.JdbcConnectionProvider;
import io.kaumei.jdbc.tx.KaumeiTxManager;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;

public final class KaumeiJdbcRepositoryCheck {
    private static void check(boolean passed, String message) {
        if (!passed) {
            throw new AssertionError(message);
        }
    }

    private static HikariDataSource dataSource(Path directory) {
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:file:" + directory.resolve("quotes")
            + ";CACHE_SIZE=4096;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        dataSource.setMinimumIdle(1);
        dataSource.setMaximumPoolSize(2);
        dataSource.setConnectionTimeout(3000);
        dataSource.setValidationTimeout(1000);
        return dataSource;
    }

    private static KaumeiQuoteRepository repository(HikariDataSource dataSource) {
        JdbcConnectionProvider connectionProvider = dataSource::getConnection;
        var transactions = KaumeiTxManager.getInstance(connectionProvider);
        KaumeiQuoteStatements statements = new KaumeiQuoteStatements$Jdbc(transactions);
        return new KaumeiQuoteRepository(transactions, statements);
    }

    public static void main(String[] args) throws Exception {
        for (String state : new String[] {"23505", "42000", null}) {
            var failure = KaumeiQuoteRepository.translate(
                new RuntimeException(new SQLException("test", state)));
            check(failure.kind() == ("23505".equals(state)
                ? RepositoryFailure.Kind.CONFLICT
                : RepositoryFailure.Kind.UNEXPECTED), "SQLState " + state);
        }

        var directory = Path.of(args[0]).toAbsolutePath();
        var dataSource = dataSource(directory);
        var repository = repository(dataSource);
        repository.initialize(dataSource);
        check(repository.state().rows() == 1170, "Fixture row count");
        check(repository.latest().size() == 3, "Latest quote count");
        var history = repository.history("AAPL", 390);
        check(history.size() == 390, "History size");
        check(history.getFirst().sequence() == 0 && history.getLast().sequence() == 389,
            "History ordering");
        check(history.getLast().price().scale() == 4, "Decimal scale");

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                 INSERT INTO quotes(batch_seq,cycle,sample_index,market_time,symbol,price)
                 VALUES(999,1,0,'09:30','GOOG',1)
                 """)) {
            statement.executeUpdate();
        }
        var beforeFailure = repository.state();
        try {
            repository.insert(new QuoteBatch(999, 1, 0, "09:30", Map.of(
                "AAPL", BigDecimal.ONE,
                "GOOG", BigDecimal.ONE,
                "NVDA", BigDecimal.ONE)));
            throw new AssertionError("Expected unique-key conflict");
        } catch (RepositoryFailure failure) {
            check(failure.kind() == RepositoryFailure.Kind.CONFLICT, "Conflict mapping");
        }
        check(repository.state().equals(beforeFailure), "Batch rollback after later-row conflict");
        check(dataSource.getHikariPoolMXBean().getActiveConnections() == 0,
            "Released failed-batch connection");

        repository.insert(new QuoteBatch(1000, 1, 1, "09:31", Map.of(
            "AAPL", new BigDecimal("10.2500"),
            "GOOG", new BigDecimal("20.5000"),
            "NVDA", new BigDecimal("30.7500"))));
        var afterInsert = repository.state();
        check(afterInsert.rows() == beforeFailure.rows() + 3, "Committed three-row batch");
        check(repository.latest().stream().allMatch(quote -> quote.sequence() == 1000),
            "Latest values after batch");
        dataSource.close();

        dataSource = dataSource(directory);
        repository = repository(dataSource);
        repository.initialize(dataSource);
        check(repository.state().equals(afterInsert), "State after database reopen");
        dataSource.close();
        System.out.println(
            "PASS: fixture, mapping, ordering, batch rollback/commit, "
                + "SQLState translation, restart persistence");
    }
}
