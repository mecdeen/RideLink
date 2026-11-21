package com.aiyu.ridelink.core.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNamesTest {
    @Test fun numbersAreNumbers() {
        assertTrue(ContactNames.isNumber("+91 98765 43210"))
        assertTrue(ContactNames.isNumber("9876543210"))
        assertTrue(ContactNames.isNumber("(080) 2345-6789"))
    }

    @Test fun namesAreNotNumbers() {
        assertFalse(ContactNames.isNumber("Amma"))
        assertFalse(ContactNames.isNumber("Incoming call"))
        assertFalse(ContactNames.isNumber("Ravi 2"))
        assertFalse(ContactNames.isNumber("112"))
    }
}
