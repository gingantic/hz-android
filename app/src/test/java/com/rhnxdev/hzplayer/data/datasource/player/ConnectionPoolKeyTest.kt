package com.rhnxdev.hzplayer.data.datasource.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards the SMB borrow/return key contract: `borrowSmbBrowser`/`returnSmbBrowser`
 * must key by the *real* password (scheme://host:port:user:passHash) or the
 * CIFSContext is never closed and leaks for the process lifetime. The password is
 * hashed, so the same password yields the same key and a different one differs.
 */
class ConnectionPoolKeyTest {

    @Test
    fun sameCreds_sameKey() {
        val a = ConnectionPool.key("smb-brw", "192.168.1.5", 445, "user", "secret")
        val b = ConnectionPool.key("smb-brw", "192.168.1.5", 445, "user", "secret")
        assertEquals(a, b)
    }

    @Test
    fun diffPass_diffKey() {
        val a = ConnectionPool.key("smb-brw", "192.168.1.5", 445, "user", "secret")
        val b = ConnectionPool.key("smb-brw", "192.168.1.5", 445, "user", "other")
        assertNotEquals(a, b)
    }

    @Test
    fun passIsHashed_notPlaintext() {
        val k = ConnectionPool.key("smb-brw", "h", 445, "u", "secret")
        assertNotEquals("secret", k.substringAfterLast(":"))
        assert(!k.contains("secret"))
    }

    @Test
    fun emptyPass_keyedWithEmptyUserAndHost() {
        // Empty password is hashed (not stored plaintext), so the key still
        // carries host/port/user and a non-empty hash segment.
        val k = ConnectionPool.key("smb-brw", "h", 445, "", "")
        assertEquals("smb-brw://h:445:", k.substringBeforeLast(":"))
        assert(k.substringAfterLast(":").isNotEmpty())
        assert(!k.contains("secret"))
    }
}
