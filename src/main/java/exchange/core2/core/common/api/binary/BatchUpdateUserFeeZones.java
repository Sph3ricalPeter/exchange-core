package exchange.core2.core.common.api.binary;

import exchange.core2.core.common.FeeZone;
import exchange.core2.core.utils.SerializationUtils;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/**
 * @author Petr Ježek
 */
@AllArgsConstructor
@EqualsAndHashCode
@Getter
public class BatchUpdateUserFeeZones implements BinaryDataCommand {

  private final LongObjectHashMap<FeeZone> userFeeZones;

  public BatchUpdateUserFeeZones(final BytesIn bytes) {
    userFeeZones = SerializationUtils.readLongHashMap(bytes, FeeZone::new);
  }

  @Override
  public int getBinaryCommandTypeCode() {
    return BinaryCommandType.UPDATE_FEE_ZONES.getCode();
  }

  @Override
  public void writeMarshallable(BytesOut bytes) {
    SerializationUtils.marshallLongHashMap(userFeeZones, bytes);
  }
}
