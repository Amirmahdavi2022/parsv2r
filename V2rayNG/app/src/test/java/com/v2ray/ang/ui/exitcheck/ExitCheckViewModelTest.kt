package com.v2ray.ang.ui.exitcheck

import com.v2ray.ang.handler.CheckedService
import com.v2ray.ang.handler.ExitCheckHttp
import com.v2ray.ang.handler.ExitLocation
import com.v2ray.ang.handler.ServiceResult
import com.v2ray.ang.handler.ServiceVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExitCheckViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val connected = ExitCheckProxy(connected = true, dynamicPort = false, httpPort = 10808, username = null, password = null)
    private val berlin = ExitLocation("1.2.3.4", "Berlin", "Berlin", "Germany", "DE", "Hetzner")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(runner: (ExitCheckProxy) -> ExitCheckHttp.Report) = ExitCheckViewModel(runner, dispatcher)

    @Test
    fun initialStateIsNotConnectedWithEveryServicePending() {
        val s = vm { error("must not run") }.state.value
        assertEquals(ExitCheckPhase.NOT_CONNECTED, s.phase)
        assertEquals(CheckedService.entries, s.rows.map { it.first })
        assertTrue(s.rows.all { it.second == null })
        assertFalse(s.canShare)
    }

    @Test
    fun notConnectedNeverRuns() {
        var ran = false
        val v = vm { ran = true; ExitCheckHttp.Report(null, emptyList()) }
        v.start(connected.copy(connected = false))
        assertFalse(ran)
        assertEquals(ExitCheckPhase.NOT_CONNECTED, v.state.value.phase)
    }

    @Test
    fun dynamicPortOrNoPortNeverRuns() {
        var ran = false
        val v = vm { ran = true; ExitCheckHttp.Report(null, emptyList()) }
        v.start(connected.copy(dynamicPort = true))
        assertEquals(ExitCheckPhase.DYNAMIC_PORT, v.state.value.phase)
        v.start(connected.copy(httpPort = 0))
        assertEquals(ExitCheckPhase.DYNAMIC_PORT, v.state.value.phase)
        assertFalse(ran)
    }

    @Test
    fun successFillsLocationAndRowsInServiceOrder() {
        val v = vm {
            // returned out of order on purpose
            ExitCheckHttp.Report(
                berlin,
                listOf(
                    ServiceResult(CheckedService.BINANCE, ServiceVerdict.BLOCKED),
                    ServiceResult(CheckedService.CHATGPT, ServiceVerdict.OK, 120),
                )
            )
        }
        v.start(connected)
        val s = v.state.value
        assertEquals(ExitCheckPhase.DONE, s.phase)
        assertEquals(berlin, s.location)
        assertEquals(CheckedService.entries, s.rows.map { it.first })
        assertEquals(ServiceVerdict.OK, s.rows.first { it.first == CheckedService.CHATGPT }.second?.verdict)
        assertEquals(ServiceVerdict.BLOCKED, s.rows.first { it.first == CheckedService.BINANCE }.second?.verdict)
        // a service missing from the report is shown as failed, not left spinning
        assertEquals(ServiceVerdict.FAILED, s.rows.first { it.first == CheckedService.CLAUDE }.second?.verdict)
        assertFalse(s.noTraffic)
        assertTrue(s.canShare)
    }

    @Test
    fun nothingAnsweringIsNoTraffic() {
        val v = vm { ExitCheckHttp.Report(null, CheckedService.entries.map { ServiceResult(it, ServiceVerdict.FAILED) }) }
        v.start(connected)
        assertTrue(v.state.value.noTraffic)
        assertFalse(v.state.value.canShare)
    }

    @Test
    fun aThrowingRunnerEndsAsNoTrafficInsteadOfCrashing() {
        val v = vm { throw IllegalStateException("boom") }
        v.start(connected)
        assertEquals(ExitCheckPhase.DONE, v.state.value.phase)
        assertNull(v.state.value.location)
        assertTrue(v.state.value.noTraffic)
    }

    @Test
    fun locationWithoutServicesIsStillShareable() {
        val v = vm { ExitCheckHttp.Report(berlin, CheckedService.entries.map { ServiceResult(it, ServiceVerdict.FAILED) }) }
        v.start(connected)
        assertFalse(v.state.value.noTraffic)
        assertTrue(v.state.value.canShare)
    }
}
