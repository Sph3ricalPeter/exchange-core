package exchange.core2.core.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author Petr Ježek
 */
@AllArgsConstructor
@Getter
public class CurrencyPair {
  int id;
  Currency base;
  Currency quote;
  long baseScale;
  long quoteScale;
}
