package exchange.core2.core.common.api.reports;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.processors.MatchingEngineRouter;
import exchange.core2.core.processors.RiskEngine;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

/** @author Petr Ježek */
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Slf4j
public final class SymbolsReportQuery implements ReportQuery<SymbolsReportResult> {

  // errors out if not present, not sure why ...
  public SymbolsReportQuery(BytesIn bytesIn) {
    // do nothing
  }

  @Override
  public int getReportTypeCode() {
    return ReportType.SYMBOLS.getCode();
  }

  @Override
  public SymbolsReportResult createResult(Stream<BytesIn> sections) {
    return SymbolsReportResult.merge(sections);
  }

  @Override
  public Optional<SymbolsReportResult> process(MatchingEngineRouter matchingEngine) {
    return Optional.empty();
  }

  @Override
  public Optional<SymbolsReportResult> process(RiskEngine riskEngine) {
    final IntObjectHashMap<CoreSymbolSpecification> symbolSpecs =
        riskEngine.getSymbolSpecificationProvider().getSymbolSpecs();
    return Optional.of(SymbolsReportResult.of(symbolSpecs));
  }

  @Override
  public void writeMarshallable(BytesOut bytes) {
    // do nothing
  }
}
