package exchange.core2.core.common.api.reports;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.utils.SerializationUtils;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

/** @author Petr Ježek */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode
@Getter
@ToString
public final class SymbolsReportResult implements ReportResult {

  private final IntObjectHashMap<CoreSymbolSpecification> symbolSpecifications;

  public SymbolsReportResult(final BytesIn bytesIn) {
    this.symbolSpecifications =
        SerializationUtils.readIntHashMap(bytesIn, CoreSymbolSpecification::new);
  }

  public static SymbolsReportResult of(
      IntObjectHashMap<CoreSymbolSpecification> symbolSpecifications) {
    return new SymbolsReportResult(symbolSpecifications);
  }

  public static SymbolsReportResult merge(final Stream<BytesIn> pieces) {
    return pieces
        .map(SymbolsReportResult::new)
        .reduce(
            SymbolsReportResult.of(null),
            (a, b) ->
                new SymbolsReportResult(
                    SerializationUtils.preferNotNull(
                        a.symbolSpecifications, b.symbolSpecifications)));
  }

  @Override
  public void writeMarshallable(BytesOut bytes) {
    SerializationUtils.marshallIntHashMap(symbolSpecifications, bytes);
  }
}
