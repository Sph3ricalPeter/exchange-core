from decimal import *

getcontext().prec = 10

LONG_MAX = 9_223_372_036_854_775_807
FEE = 0

9223372036854775807


class Pair():
    def __init__(
        self,
        baseScale: Decimal,
        quoteScale: Decimal,
        nUnitsBase: Decimal = Decimal(100_000_000),
        nUnitsQuote: Decimal = Decimal(100_000_000)
    ) -> None:
        self.baseScale = baseScale
        self.quoteScale = quoteScale
        self.nUnitsBase = nUnitsBase
        self.nUnitsQuote = nUnitsQuote


class Convertor():
    def __init__(self,
                 pair: Pair,
                 price: Decimal = None,
                 size: Decimal = None,
                 total: Decimal = None):
        self.pair = pair
        self.pricePerLotScaled = self.convertPrice(price)

        if (size is None):
            self.totalPriceScaled = total * self.pair.nUnitsQuote
            """ print(
                f"totalPriceScaled {self.totalPriceScaled}, pricePerLotScaled {self.pricePerLotScaled}"
            ) """
            self.sizeLots = self.totalPriceScaled / self.pricePerLotScaled
        else:
            self.sizeLots = self.convertSize(size)
            self.totalPriceScaled = self.calcTotal()

    # convert price from price per 1 base to price per 1 lot of base in quote scale
    def convertPrice(self, price):
        nLots = self.pair.nUnitsBase / self.pair.baseScale
        ppLot = price / nLots
        ppLotUnits = ppLot * self.pair.nUnitsQuote
        return ppLotUnits / self.pair.quoteScale  # ((price / (self.pair.nUnitsBase / self.pair.baseScale)) * self.pair.nUnitsQuote) / self.pairQuoteScale

    # convert size from X base to X lots of base
    def convertSize(self, size):
        return size * self.pair.nUnitsBase / self.pair.baseScale

    def calcTotal(self):
        return self.sizeLots * (self.pricePerLotScaled * self.pair.quoteScale +
                                FEE)

    def totalCalcToString(self):
        return f'{self.sizeLots} * ({self.pricePerLotScaled} * {self.pair.quoteScale} + {FEE})'

    def toString(self):
        return f'baseScale={self.pair.baseScale}, quoteScale={self.pair.quoteScale}, pricePerLotScaled={self.pricePerLotScaled}, sizeLots={self.sizeLots}, totalPriceScaled={self.totalPriceScaled}'


# Pairs
# PAIR_BTC_LTC = Pair(baseScale=Decimal(1_000_000), quoteScale=Decimal(10_000))
PAIR_LTC_BTC = Pair(baseScale=Decimal(10_000), quoteScale=Decimal(1))

# COINMATE RANGE MIN
c = Convertor(pair=PAIR_LTC_BTC, price=Decimal(0.00001), size=Decimal(0.0001))
print(c.toString(), end=", ")
print(c.totalCalcToString())

# COINMATE RANGE MAX, seems fine
c = Convertor(
    pair=PAIR_LTC_BTC,
    price=Decimal(
        5
    ),  # price per bitcion is about 1724x the current real price, should be enough headroom
    size=Decimal(10_000_000_000)
)  # can trade 2 trillion dollars worth of LTC in one T, not bad
print(c.toString(), end=", ")
print(c.totalCalcToString())

# Testing
c = Convertor(pair=PAIR_LTC_BTC, price=Decimal(0.003), size=1)
print(c.toString(), end=", ")
print(c.totalCalcToString())

# Scenario 1 - everything fits, it all good
""" c = Convertor(pair=PAIR_BTC_LTC, price=Decimal(154), size=Decimal(0.12))
print(c.toString(), end=", ")
print(c.totalCalcToString())

c = Convertor(pair=PAIR_BTC_LTC, price=Decimal(154), total=Decimal(18.48))
print(c.toString(), end=", ")
print(c.totalCalcToString()) """
""" # Scenario 2
s = Symbol(Decimal(100_000), Decimal(10_000))
print(s.toString(), end=", ")
print(s.totalCalcToString())

# Scenario 3
s = Symbol(Decimal(1_000_000), Decimal(1_000))
print(s.toString(), end=", ")
print(s.totalCalcToString())

# Scenario 4
s = Symbol(Decimal(100_000), Decimal(1_000))
print(s.toString(), end=", ")
print(s.totalCalcToString())

# Scenario 5 - price is too small
s = Symbol(Decimal(1_000), Decimal(100_000))
print(s.toString(), end=", ")
print(s.totalCalcToString())

# Scenario 6 - we fix smol price, don ez :)
s = Symbol(Decimal(1_000), Decimal(1_000))
print(s.toString(), end=", ")
print(s.totalCalcToString()) """
