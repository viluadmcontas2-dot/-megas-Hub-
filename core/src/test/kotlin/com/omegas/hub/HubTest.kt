package com.omegas.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class HubTest {
    @Test fun identidadeFixa() {
        assertEquals("com.omegas.hub", Hub.APPLICATION_ID)
        assertEquals("omegas-session-log-v1", Hub.SESSION_SCHEMA)
    }
}
