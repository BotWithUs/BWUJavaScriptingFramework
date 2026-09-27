package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.core.sdn.SdnPrice;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorePricesTest {

    @ParameterizedTest
    @CsvSource({
            "one_time, 4.99 once",
            "two_day, 4.99 per 2 days",
            "weekly, 4.99 per week",
            "fortnightly, 4.99 per 2 weeks",
            "monthly, 4.99 per month",
            "yearly, 4.99 per year",
            "quarterly_ish, 4.99 quarterly ish"})
    void describe_wordsEveryPeriodTheSiteSends(String duration, String expected) {
        assertEquals(expected, StorePrices.describe(new SdnPrice(new BigDecimal("4.99"), duration)));
    }

    @Test
    void describe_showsCentsForAWholeAmount_andKeepsExtraPrecision() {
        assertEquals("5.00 per month", StorePrices.describe(new SdnPrice(new BigDecimal("5"), "monthly")));
        assertEquals("0.125 per month", StorePrices.describe(new SdnPrice(new BigDecimal("0.125"), "monthly")));
    }

    @Test
    void describe_noPrice_isEmpty() {
        assertEquals("", StorePrices.describe(null));
    }
}
