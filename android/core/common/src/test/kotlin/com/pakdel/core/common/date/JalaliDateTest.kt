package com.pakdel.core.common.date

import org.junit.Assert.assertEquals
import org.junit.Test

class JalaliDateTest {

    @Test
    fun `nowruz 1403 converts correctly`() {
        val date = JalaliDate.fromGregorian(2024, 3, 20)
        assertEquals(1403, date.year)
        assertEquals(1, date.month)
        assertEquals(1, date.day)
    }

    @Test
    fun `mid year date converts correctly`() {
        val date = JalaliDate.fromGregorian(2024, 8, 11)
        assertEquals(1403, date.year)
        assertEquals(5, date.month)
        assertEquals(21, date.day)
    }

    @Test
    fun `format uses persian digits`() {
        val formatted = JalaliDate(1403, 5, 21).format()
        assertEquals("۱۴۰۳/۰۵/۲۱", formatted)
    }

    @Test
    fun `esfand has 29 days in non leap year`() {
        assertEquals(29, JalaliDate.daysInMonth(1404, 12))
    }

    @Test
    fun `1403 is a leap year and esfand has 30 days`() {
        assertEquals(true, JalaliDate.isLeapYear(1403))
        assertEquals(30, JalaliDate.daysInMonth(1403, 12))
    }

    @Test
    fun `1399 and 1408 are leap years`() {
        assertEquals(true, JalaliDate.isLeapYear(1399))
        assertEquals(true, JalaliDate.isLeapYear(1408))
        assertEquals(false, JalaliDate.isLeapYear(1402))
    }

    @Test
    fun `toman format pads thousands`() {
        assertEquals("۱٬۲۵۰٬۰۰۰", com.pakdel.core.common.money.Toman(1_250_000).format())
    }

    @Test
    fun `percent discount rounds half up`() {
        val discount = com.pakdel.core.common.money.Toman.percentOf(
            com.pakdel.core.common.money.Toman(125_000), 10
        )
        assertEquals(12_500, discount.amount)
    }
}
