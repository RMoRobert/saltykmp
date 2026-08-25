package com.enuvro.saltykmp.di

import com.sun.jna.Native
import com.sun.jna.WString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JNA computes struct layout from the declared fields on every platform, so the parts of the Windows
 * binding that are *not* the four native calls can be checked from any host. This catches the failure
 * modes that silently produce a credential Windows won't store: a field JNA can't reach, a wrong type,
 * or an offset that doesn't match wincred.h.
 */
class CredentialwLayoutTest {

    @Test
    fun matches_the_wincred_h_layout() {
        // 64-bit: 4+4 +8 +8 +8 +4(+4 pad) +8 +4+4 +8 +8 +8 = 80.  32-bit: 52.
        val expected = if (Native.POINTER_SIZE == 8) 80 else 52
        assertEquals(expected, CREDENTIALW().size())
    }

    @Test
    fun fields_are_reachable_and_writable_by_jna() {
        // Throws if JNA cannot access the fields reflectively -- the exact failure the production code
        // used to swallow in a runCatching.
        val cred = CREDENTIALW().apply {
            type = 1
            targetName = WString("Salty:password")
            credentialBlobSize = 8
            persist = 2
            userName = WString("rob")
        }
        cred.write()
        cred.read()
        assertEquals(1, cred.type)
        assertEquals(2, cred.persist)
        assertEquals(8, cred.credentialBlobSize)
    }
}
