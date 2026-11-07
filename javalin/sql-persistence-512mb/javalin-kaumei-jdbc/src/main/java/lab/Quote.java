package lab;

import io.kaumei.jdbc.annotation.JdbcName;

import java.math.BigDecimal;

public record Quote(
        @JdbcName("batch_seq") int sequence,
        int cycle,
        int sampleIndex,
        String marketTime,
        String symbol,
        BigDecimal price) {
}
