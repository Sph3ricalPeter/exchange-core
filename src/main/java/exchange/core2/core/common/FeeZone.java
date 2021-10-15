package exchange.core2.core.common;

import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * @author Petr Ježek
 */
@ToString
public class FeeZone implements WriteBytesMarshallable, StateHash {

  // TODO: this is for testing purposes only
  public static final FeeZone NONE = FeeZone.fromPercent(0, 0);

  public double makerFeeFraction;
  public double takerFeeFraction;

  private FeeZone(double makerFeeFraction, double takerFeeFraction) {
    this.makerFeeFraction = makerFeeFraction;
    this.takerFeeFraction = takerFeeFraction;
  }

  public FeeZone(BytesIn bytesIn) {
    makerFeeFraction = bytesIn.readDouble();
    takerFeeFraction = bytesIn.readDouble();
  }

  public static FeeZone fromPercent(double makerFeePercent, double takerFeePercent) {
    return new FeeZone(makerFeePercent / 100, takerFeePercent / 100);
  }

  @Override
  public void writeMarshallable(BytesOut bytes) {
    bytes.writeDouble(makerFeeFraction);
    bytes.writeDouble(takerFeeFraction);
  }

  @Override
  public int stateHash() {
    return Objects.hash(makerFeeFraction, takerFeeFraction);
  }
}
