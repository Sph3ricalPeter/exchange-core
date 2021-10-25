package exchange.core2.core.utils;

import java.math.BigDecimal;

/** @author Petr Ježek */
public class Convert {

  public static long priceToPricePerLot(CurrencyPair pair, BigDecimal price) {
    return price
        .multiply(
            new BigDecimal(
                (pair.quote.nUnits * pair.baseScale) / (pair.base.nUnits * pair.quoteScale)))
        .longValue();
  }

  public static long sizeToLots(CurrencyPair pair, BigDecimal size) {
    return size.multiply(new BigDecimal(pair.base.nUnits / pair.baseScale)).longValue();
  }

  public static long calcTotalScaled(CurrencyPair pair, long sizeLots, long pricePerLotScaled) {
    return sizeLots * (pricePerLotScaled * pair.quoteScale); // base fee is 0:
  }

  public static boolean isWithinRange(BigDecimal amount, BigDecimal low, BigDecimal high) {
    return amount.compareTo(low) >= 0 && amount.compareTo(high) <= 0;
  }
}
