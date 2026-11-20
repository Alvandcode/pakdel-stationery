package com.pakdel.core.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAndTimestampTest {

    @Test
    fun `postgrest offset timestamp parses to correct epoch`() {
        val millis = parseTimestampOrNull("2024-03-20T12:00:00+00:00")
        assertEquals(1710936000000L, millis)
    }

    @Test
    fun `postgrest zulu timestamp with micros parses`() {
        val millis = parseTimestampOrNull("2024-03-20T12:00:00.123456Z")
        assertEquals(1710936000123L, millis)
    }

    @Test
    fun `timestamp without zone uses tehran`() {
        val millis = parseTimestampOrNull("2024-03-20 12:00:00")
        // ۱۲:۰۰ تهران = ۰۸:۳۰ UTC
        assertEquals(1710923400000L, millis)
    }

    @Test
    fun `invalid or empty timestamp returns null`() {
        assertNull(parseTimestampOrNull(null))
        assertNull(parseTimestampOrNull(""))
        assertNull(parseTimestampOrNull("not-a-date"))
    }

    @Test
    fun `cashier cannot cancel sale or adjust stock`() {
        val session = Session("u", "e", "s", "cashier", "صندوق‌دار")
        assertTrue(session.canCreateSale)
        assertTrue(session.canManageCustomers)
        assertEquals(false, session.canCancelSale)
        assertEquals(false, session.canAdjustStock)
        assertEquals(false, session.canManageStaff)
        assertEquals(false, session.canSeeProfit)
    }

    @Test
    fun `owner has every critical permission`() {
        val session = Session("u", "e", "s", "owner", "مالک")
        assertTrue(session.canCreateSale)
        assertTrue(session.canCancelSale)
        assertTrue(session.canAdjustStock)
        assertTrue(session.canManageProducts)
        assertTrue(session.canManageStaff)
        assertTrue(session.canSeeProfit)
    }

    @Test
    fun `warehouse cannot sell`() {
        val session = Session("u", "e", "s", "warehouse", "انبار")
        assertEquals(false, session.canCreateSale)
        assertTrue(session.canAdjustStock)
        assertTrue(session.canManageProducts)
    }
}
