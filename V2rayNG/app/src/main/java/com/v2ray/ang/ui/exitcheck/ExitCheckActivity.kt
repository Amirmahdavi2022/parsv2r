package com.v2ray.ang.ui.exitcheck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.CheckedService
import com.v2ray.ang.handler.ExitCheckLogic
import com.v2ray.ang.handler.ExitLocation
import com.v2ray.ang.handler.ExitPlace
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ServiceResult
import com.v2ray.ang.handler.ServiceVerdict
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import java.util.Locale

/**
 * Shows where the connected config comes out (IP, city, country, provider) and which
 * services open through it. Opened from the drawer; MainActivity passes whether a config
 * is connected, since the core runs in another process and can't be asked from here.
 */
class ExitCheckActivity : BaseComponentActivity() {

    private val viewModel: ExitCheckViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) runCheck()
    }

    private fun proxy(): ExitCheckProxy = ExitCheckProxy(
        connected = intent.getBooleanExtra(EXTRA_CONNECTED, false),
        // The dynamic port lives in the core's process only; this one can't know it.
        dynamicPort = MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false),
        httpPort = SettingsManager.getHttpPort(),
        username = SettingsManager.getSocksUsername(),
        password = SettingsManager.getSocksPassword(),
        guid = MmkvManager.getSelectServer(),
    )

    private fun runCheck() = viewModel.start(proxy())

    private fun share(state: ExitCheckUiState) {
        val text = ExitCheckLogic.shareText(
            location = state.location?.let { loc ->
                // only what the databases agree on goes into the shared text
                val p = state.place
                val known = p?.known == true
                loc.copy(
                    city = if (known) p?.city else null,
                    countryCode = if (known) p?.countryCode else null,
                    country = when {
                        known -> p?.countryCode?.let { ExitCheckLogic.countryLabel(it) }
                        p?.anycast == true -> getString(R.string.exit_place_anycast)
                        else -> getString(R.string.exit_place_unknown)
                    },
                )
            },
            results = state.rows.mapNotNull { it.second },
            header = getString(R.string.exit_check_share_header),
            locationLabel = getString(R.string.exit_check_share_location),
            nameOf = { serviceName(this, it) },
            footer = getString(R.string.exit_check_share_footer),
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.exit_check_share)))
    }

    private fun copyIp(ip: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ip", ip))
        toast(R.string.exit_check_copied)
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        ExitCheckScreen(
            state = state,
            onBack = { finish() },
            onRetry = { runCheck() },
            onShare = { share(state) },
            onCopyIp = { copyIp(it) },
        )
    }

    companion object {
        const val EXTRA_CONNECTED = "exit_check_connected"
    }
}

/** Country in the app's language when Android knows the code, else what the service sent. */
fun countryName(l: ExitLocation): String? {
    val code = l.countryCode
    if (code != null) {
        val name = Locale("", code).getDisplayCountry(Locale.getDefault())
        if (name.isNotBlank() && name != code) return name
    }
    return l.country ?: code
}

fun serviceName(context: Context, s: CheckedService): String = when (s) {
    CheckedService.CHATGPT -> "ChatGPT"
    CheckedService.CLAUDE -> "Claude"
    CheckedService.GOOGLE -> context.getString(R.string.exit_check_google)
    CheckedService.YOUTUBE -> "YouTube"
    CheckedService.INSTAGRAM -> "Instagram"
    CheckedService.TELEGRAM -> "Telegram"
    CheckedService.BINANCE -> "Binance"
}

private val Green = Color(0xFF2E9E5B)
private val Red = Color(0xFFD64545)
private val Amber = Color(0xFFE0A030)

@Composable
private fun verdictColor(v: ServiceVerdict?): Color = when (v) {
    ServiceVerdict.OK -> Green
    ServiceVerdict.BLOCKED -> Red
    ServiceVerdict.LIMITED, ServiceVerdict.CAPTCHA -> Amber
    ServiceVerdict.FAILED -> MaterialTheme.colorScheme.outline
    null -> MaterialTheme.colorScheme.outlineVariant
}

@Composable
private fun verdictText(v: ServiceVerdict?): String = stringResource(
    when (v) {
        ServiceVerdict.OK -> R.string.exit_check_verdict_ok
        ServiceVerdict.BLOCKED -> R.string.exit_check_verdict_blocked
        ServiceVerdict.LIMITED -> R.string.exit_check_verdict_limited
        ServiceVerdict.CAPTCHA -> R.string.exit_check_verdict_captcha
        ServiceVerdict.FAILED -> R.string.exit_check_verdict_failed
        null -> R.string.exit_check_verdict_testing
    }
)

@Composable
fun ExitCheckScreen(
    state: ExitCheckUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onShare: () -> Unit,
    onCopyIp: (String) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.exit_check_title),
                onBackClick = onBack,
                isLoading = state.phase == ExitCheckPhase.RUNNING,
                actions = {
                    if (state.canShare) {
                        IconButton(onClick = onShare) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share_24dp),
                                contentDescription = stringResource(R.string.exit_check_share)
                            )
                        }
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state.phase) {
                ExitCheckPhase.NOT_CONNECTED -> MessageCard(
                    title = stringResource(R.string.exit_check_not_connected_title),
                    body = stringResource(R.string.exit_check_not_connected_body)
                )

                ExitCheckPhase.DYNAMIC_PORT -> MessageCard(
                    title = stringResource(R.string.exit_check_title),
                    body = stringResource(R.string.exit_check_dynamic_port)
                )

                ExitCheckPhase.RUNNING, ExitCheckPhase.DONE -> {
                    if (state.noTraffic) {
                        MessageCard(
                            title = stringResource(R.string.exit_check_no_traffic_title),
                            body = stringResource(R.string.exit_check_no_traffic_body)
                        )
                    } else {
                        LocationCard(
                            location = state.location,
                            place = state.place,
                            loading = state.phase == ExitCheckPhase.RUNNING,
                            onCopyIp = onCopyIp
                        )
                    }
                    ServicesCard(rows = state.rows)
                    Text(
                        text = stringResource(R.string.exit_check_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PressScaleButton(
                            text = stringResource(R.string.exit_check_run_again),
                            enabled = state.phase == ExitCheckPhase.DONE,
                            primary = !state.canShare,
                            onClick = onRetry,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.canShare) {
                            PressScaleButton(
                                text = stringResource(R.string.exit_check_share),
                                enabled = true,
                                primary = true,
                                onClick = onShare,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            NavigationBarsSpacer()
        }
    }
}

@Composable
private fun MessageCard(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LocationCard(location: ExitLocation?, place: ExitPlace?, loading: Boolean, onCopyIp: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        AnimatedContent(
            targetState = location to loading,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "location"
        ) { (loc, isLoading) ->
            if (loc == null && isLoading) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.exit_check_locating), style = MaterialTheme.typography.bodyMedium)
                }
            } else if (loc != null) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.exit_check_exit_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val known = place?.known == true
                        val flag = if (known) ExitCheckLogic.flagEmoji(place?.countryCode) else "🌐"
                        if (flag.isNotEmpty()) {
                            Text(flag, fontSize = 40.sp, modifier = Modifier.clearAndSetSemantics { })
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            val cc = place?.countryCode
                            Text(
                                text = when {
                                    place?.anycast == true -> stringResource(R.string.exit_place_anycast).removePrefix("🌐 ")
                                    !known || cc == null -> stringResource(R.string.exit_place_unknown).removePrefix("🌐 ")
                                    else -> place?.city ?: ExitCheckLogic.countryLabel(cc ?: "")
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (known && cc != null && place?.city != null) {
                                Text(
                                    text = ExitCheckLogic.countryLabel(cc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (!loc.isp.isNullOrBlank()) {
                        Text(
                            text = loc.isp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (!loc.ip.isNullOrBlank()) {
                        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.exit_check_ip),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = loc.ip,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start
                            )
                            IconButton(onClick = { onCopyIp(loc.ip) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_copy),
                                    contentDescription = stringResource(R.string.exit_check_copy_ip),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.exit_check_unknown_city),
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ServicesCard(rows: List<Pair<CheckedService, ServiceResult?>>) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.exit_check_services),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            rows.forEach { (service, result) ->
                val name = serviceName(context, service)
                val status = verdictText(result?.verdict)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics { contentDescription = "$name, $status" }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    AnimatedContent(
                        targetState = result,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) },
                        label = "verdict"
                    ) { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (r == null) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(verdictColor(r.verdict), CircleShape)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = verdictText(r?.verdict),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (r == null) MaterialTheme.colorScheme.onSurfaceVariant else verdictColor(r.verdict)
                                )
                                val ms: Long? = if (r != null && r.verdict == ServiceVerdict.OK) r.latencyMs else null
                                if (ms != null) {
                                    Text(
                                        text = stringResource(R.string.exit_check_ms, ms.toInt()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A button that presses in slightly, so a tap is felt before the work starts. */
@Composable
private fun PressScaleButton(
    text: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "press"
    )
    val m = modifier
        .height(52.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale }
    if (primary) {
        Button(onClick = onClick, enabled = enabled, interactionSource = interaction, modifier = m, shape = RoundedCornerShape(16.dp)) {
            Text(text)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, interactionSource = interaction, modifier = m, shape = RoundedCornerShape(16.dp)) {
            Text(text)
        }
    }
}
