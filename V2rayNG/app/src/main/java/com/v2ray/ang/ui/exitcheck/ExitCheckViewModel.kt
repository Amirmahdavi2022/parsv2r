package com.v2ray.ang.ui.exitcheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.handler.CheckedService
import com.v2ray.ang.handler.ExitCheckHttp
import com.v2ray.ang.handler.ExitLocation
import com.v2ray.ang.handler.ExitPlace
import com.v2ray.ang.handler.ExitPlaceStore
import com.v2ray.ang.handler.ServiceResult
import com.v2ray.ang.handler.ServiceVerdict
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExitCheckPhase { NOT_CONNECTED, DYNAMIC_PORT, RUNNING, DONE }

data class ExitCheckUiState(
    val phase: ExitCheckPhase = ExitCheckPhase.NOT_CONNECTED,
    val location: ExitLocation? = null,
    /** What the IP databases agree on; drives the headline. */
    val place: ExitPlace? = null,
    /** One entry per service, in display order; null verdict while still testing. */
    val rows: List<Pair<CheckedService, ServiceResult?>> = CheckedService.entries.map { it to null },
) {
    /** Done, and nothing at all answered: the tunnel is up but carries no traffic. */
    val noTraffic: Boolean
        get() = phase == ExitCheckPhase.DONE && location == null &&
            rows.all { it.second?.verdict == ServiceVerdict.FAILED }

    val canShare: Boolean
        get() = phase == ExitCheckPhase.DONE && !noTraffic
}

/** Where the local proxy is and how to reach it, read from settings by the activity. */
data class ExitCheckProxy(
    val connected: Boolean,
    val dynamicPort: Boolean,
    val httpPort: Int,
    val username: String?,
    val password: String?,
    /** The connected config, so the place found here also shows on its row. */
    val guid: String? = null,
)

class ExitCheckViewModel @JvmOverloads constructor(
    private val runner: (ExitCheckProxy) -> ExitCheckHttp.Report = { p ->
        ExitCheckHttp(p.httpPort, p.username, p.password).run().also { r ->
            r.place?.let { ExitPlaceStore.record(p.guid, it) }
        }
    },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(ExitCheckUiState())
    val state: StateFlow<ExitCheckUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun start(proxy: ExitCheckProxy) {
        when {
            !proxy.connected -> {
                _state.value = ExitCheckUiState(phase = ExitCheckPhase.NOT_CONNECTED)
                return
            }

            proxy.dynamicPort || proxy.httpPort <= 0 -> {
                _state.value = ExitCheckUiState(phase = ExitCheckPhase.DYNAMIC_PORT)
                return
            }
        }
        if (job?.isActive == true) return
        _state.value = ExitCheckUiState(phase = ExitCheckPhase.RUNNING)
        job = viewModelScope.launch {
            val report = withContext(io) {
                try {
                    runner(proxy)
                } catch (_: Exception) {
                    ExitCheckHttp.Report(null, CheckedService.entries.map { ServiceResult(it, ServiceVerdict.FAILED) })
                }
            }
            val byService = report.results.associateBy { it.service }
            _state.value = ExitCheckUiState(
                phase = ExitCheckPhase.DONE,
                location = report.location,
                place = report.place,
                rows = CheckedService.entries.map {
                    it to (byService[it] ?: ServiceResult(it, ServiceVerdict.FAILED))
                },
            )
        }
    }
}
