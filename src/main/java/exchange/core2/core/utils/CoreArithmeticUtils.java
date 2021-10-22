/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.core.utils;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.FeeZone;
import exchange.core2.core.common.UserProfile;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class CoreArithmeticUtils {

    public static long calculateAmountAsk(long size, CoreSymbolSpecification spec) {
        return size * spec.baseScaleK;
    }

    public static long calculateAmountBid(long size, long price, CoreSymbolSpecification spec) {
        return size * (price * spec.quoteScaleK);
    }

    // calculate amount of quote for bidding taker including taker fee
    // zone fee is calculated from base = size * price * quoteScale
    // base fee remains only dependent on order size
    public static long calculateAmountBidTakerFee(long size, long price, CoreSymbolSpecification spec, FeeZone feeZone) {
        log.info("calculateAmountBidTakerFee: {} * ((1 + {}) * {} * {} + {}", size, feeZone.takerFeeFraction, price, spec.quoteScaleK, spec.takerBaseFee);
        return Math.round(size * ((1 + feeZone.takerFeeFraction) * price * spec.quoteScaleK + spec.takerBaseFee));
    }

    public static long calculateAmountBidReleaseCorrMaker(long size, long price, long priceDiff, CoreSymbolSpecification spec, FeeZone feeZone) {
        log.info("calculateAmountBidReleaseCorrMaker: {} * (({} - {}) * {} * {} + ({} - {})))", size, feeZone.takerFeeFraction, feeZone.makerFeeFraction, priceDiff, spec.quoteScaleK, spec.takerBaseFee, spec.makerBaseFee);
        // TODO: if price diff is 0, fees from zone don't apply at all
        return Math.round(size * ((feeZone.takerFeeFraction - feeZone.makerFeeFraction) * price * spec.quoteScaleK + priceDiff * spec.quoteScaleK + (spec.takerBaseFee - spec.makerBaseFee)));
    }

    // TODO: budget? will not be used most likely ...
    public static long calculateAmountBidTakerFeeForBudget(long size, long budgetInSteps, CoreSymbolSpecification spec, FeeZone feeZone) {
        // calculate % fee from budget but base fee from size?
        return Math.round((1 + feeZone.takerFeeFraction) * budgetInSteps * spec.quoteScaleK + size * spec.takerBaseFee);
    }

}
