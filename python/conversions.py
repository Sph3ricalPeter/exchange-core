from decimal import *

getcontext().prec = 10

LONG_MAX = 9_223_372_036_854_775_807

# INPUT
INPUT_PRICE = Decimal(154)
INPUT_SIZE = Decimal(18.48)
INPUT_TOTAL = Decimal(0.12)

# ENGINE

N_UNITS = Decimal(100_000_000)
FEE = 0


class Symbol():
    def __init__(self, baseScale, quoteScale):
        self.baseScale = baseScale
        self.quoteScale = quoteScale
        self.price = self.calcPrice()
        self.size = self.calcSize()
        self.total = self.calcTotal()

    def calcPrice(self):
        nLots = N_UNITS / self.baseScale
        ppLotLTC = INPUT_PRICE / nLots
        ppLotLits = ppLotLTC * N_UNITS
        self.price = ppLotLits / self.quoteScale
        return self.price

    def calcSize(self):
        self.size = INPUT_TOTAL * N_UNITS / self.baseScale
        return self.size

    def calcTotal(self):
        self.total = self.size * (self.price * self.quoteScale + FEE)
        return self.total

    def totalCalcToString(self):
        return f'{self.size} * ({self.price} * {self.quoteScale} + {FEE})'

    def toString(self):
        return f'baseScale={self.baseScale}, quoteScale={self.quoteScale}, price={self.price}, size={self.size}, total={self.total}'


# Scenario 1 - everything fits, it all good
s = Symbol(Decimal(1_000_000), Decimal(10_000))
print(s.toString(), end=", ")
print(s.totalCalcToString())

# Scenario 2
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
print(s.totalCalcToString())
