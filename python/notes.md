# BTC/LTC - base is BTC (buy/sell BTC), price is LTC

## buy order interface:

size=? (calculated)
price=154 (ltc per btc)
total=0.12 (btc spent)

updating size updates total
updating price updates total
updating total updates size
in other words
size -> total
price -> total
total -> size

size = 154 \* 0.12 = 18.48

## core engine:

SCALE = STEP (eg. BTC/LTC, quoteScale=10_000 means LTC jumps in increments of 10_000, scaled value of 100_000 litoshi is then 10, and it means 10 \* 10_000 - 10 steps)

inputSize = 18.48, needs to be scaled
inputPrice = 154
inputTotal = 0.12, needs to be scaled

nUnits = 100_000_000

# Scenario 1

baseScale=1_000_000 (BTC step is 0.01)
quoteScale=10_000 (LTC step is 0.0001)

nlots = nUnits / baseScale = 100*000_000 / 1_000_000 = 100
ppLotLTC = inputPrice / nLots = 154 / 100 = 1.54
ppLotLits = ppLotLTC * nUnits = 1.54 \_ 100_000_000 = 154_000_000
ppLotLitsInScale = ppLotLits / quoteScale = 15_400

price = ppLotLitsInScale = 15*400
size = inputTotal * nUnits / baseScale = 0.12 _ 100_000_000 / 1_000_000 = 12_000_000 / 1_000_000 = 12
total = size _ (price _ quoteScale + fee) = 12 _ (15*400 * 10_000 + 0) = 1848000000 # calculated in arithmetic utils

# Scenario 2

baseScale=1_000 (BTC step is 0.00001)
quoteScale=100_000 (LTC step is 0.001)

nlots = nUnits / baseScale = 100*000_000 / 1_000 = 100_000
ppLotLTC = inputPrice / nLots = 154 / 100_000 = 0,00154
ppLotLits = ppLotLTC * nUnits = 0,00154 \_ 100_000_000 = 154_000
ppLotLitsInScale = ppLotLits / quoteScale = 154_000 / 100_000 = 1.54 # rip, we cant put that into the core

price = ppLotLitsInScale = 1.54
size = inputTotal _ uUnits / baseScale = 0.12 _ 100*000_000 / 1_000 = 12_000
total = size * (price _ quoteScale + fee) = 12_000 _ (1.54 \_ 100_000 + 0) = 1848000000

so everything comes down to the last equation: total = size _ (price _ quoteScale + fee)
we need to redistribute precision between size, price and quoteScale such that all of them can be stored in a long and we dont lose any precision information
eg:
12*000 * (1.54 \_ 100_000) we can see that price=1.54 could be 100 times larger, while quoteScale=100_000 could be 100x lower without losing any precision
