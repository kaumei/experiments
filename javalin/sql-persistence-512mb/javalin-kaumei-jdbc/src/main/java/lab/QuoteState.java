package lab;
import io.kaumei.jdbc.annotation.JdbcName;
import java.math.BigDecimal;
public record QuoteState(@JdbcName("row_count") int rows, int maxSequence, BigDecimal priceSum) { }
