package io.music_assistant.client

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.asLiveData
import co.touchlab.kermit.Logger
import io.music_assistant.client.api.DeepLinkBus
import io.music_assistant.client.auth.AuthenticationManager
import io.music_assistant.client.auth.CustomTabsOAuthHandler
import io.music_assistant.client.auth.OAuthHandler
import io.music_assistant.client.data.MainDataSource
import io.music_assistant.client.input.VolumeButtonService
import io.music_assistant.client.services.MainMediaPlaybackService
import io.music_assistant.client.settings.SettingsRepository
import io.music_assistant.client.ui.compose.App
import io.music_assistant.client.ui.compose.common.action.PlayerAction
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val dataSource: MainDataSource by inject()
    private val authManager: AuthenticationManager by inject()
    private val deepLinkBus: DeepLinkBus by inject()
    private val volumeButtonService: VolumeButtonService by inject()
    private val settings: SettingsRepository by inject()
    private val oauthHandler: OAuthHandler by lazy {
        CustomTabsOAuthHandler(this)
    }
    private var remoteVolumeKeyActive = false
    private var lastRemoteVolumeStepMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Lock orientation on compact devices, unless the user opted out. The
        // manifest declares no screenOrientation, so re-applying this live only
        // triggers a configuration change the activity already handles itself.
        settings.allowLandscapeOnAllDevices.asLiveData()
            .observe(this) { allowLandscape ->
                requestedOrientation = if (!allowLandscape && isCompactDevice()) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }

        // Provide OAuthHandler to AuthenticationManager
        authManager.oauthHandler = oauthHandler

        // Handle OAuth callback / page deep link if launched from one
        handleIncomingUri(intent)

        dataSource.isAnythingPlaying.asLiveData()
            .observe(this) {
                if (it) {
                    val serviceIntent = Intent(this, MainMediaPlaybackService::class.java)
                    serviceIntent.action = "ACTION_PLAY"
                    try {
                        startForegroundService(serviceIntent)
                    } catch (e: Exception) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            e is ForegroundServiceStartNotAllowedException
                        ) {
                            Logger.withTag("MainActivity")
                                .w("Cannot start foreground service from background, will retry when foregrounded")
                        } else {
                            throw e
                        }
                    }
                }
            }
        setContent {
            App()
        }
    }

    private fun isCompactDevice() =
        resources.configuration.smallestScreenWidthDp <= COMPACT_DEVICE_WIDTH

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask reuses this instance, so this path is now live (it was dead
        // under the default standard launchMode). Keep getIntent() in sync.
        setIntent(intent)
        handleIncomingUri(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (stepRemoteVolume(up = keyCode == KeyEvent.KEYCODE_VOLUME_UP, event)) return true
            if (event?.repeatCount == 0) volumeButtonService.onPlatformVolumeButtonPressed()
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // The system routes the up event to its volume handling too; swallow it for keys we handled.
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            remoteVolumeKeyActive
        ) {
            remoteVolumeKeyActive = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * While the app is in the foreground on a remote player, the volume keys step that player's
     * volume like a tap beside the slider does (group volume when members are bound). The local
     * player keeps the system volume. Held keys are throttled so they don't flood the server.
     */
    private fun stepRemoteVolume(up: Boolean, event: KeyEvent?): Boolean {
        val player = dataSource.selectedPlayer
            ?.takeIf { !it.isLocal && it.player.isVolumeSliderAccessible }
            ?: return false
        remoteVolumeKeyActive = true
        val now = SystemClock.uptimeMillis()
        if ((event?.repeatCount ?: 0) > 0 && now - lastRemoteVolumeStepMs < REMOTE_VOLUME_REPEAT_MS) {
            return true
        }
        lastRemoteVolumeStepMs = now
        val group = player.childrenBinds.any { it.isBound }
        dataSource.playerAction(
            player,
            when {
                group && up -> PlayerAction.GroupVolumeUp
                group -> PlayerAction.GroupVolumeDown
                up -> PlayerAction.VolumeUp
                else -> PlayerAction.VolumeDown
            },
        )
        return true
    }

    /**
     * Single dispatch point for incoming deep-link URIs. The OAuth callback is
     * peeled off explicitly; everything else (musicassistant://app/<page> and
     * the https App Link https://music-assistant.io/app/<page>) is forwarded to
     * [DeepLinkBus], which self-filters and ignores anything it doesn't
     * recognize. Only URIs matching a manifest intent-filter ever reach here.
     */
    private fun handleIncomingUri(intent: Intent?) {
        val data = intent?.data ?: return
        Logger.withTag("MainActivity").d("Deep link received: $data")

        // musicassistant://auth/callback?code=...
        if (authManager.handleOAuthCallbackUrl(data.toString())) return
        deepLinkBus.handle(data.toString())
    }

    companion object {
        private const val COMPACT_DEVICE_WIDTH = 600
        private const val REMOTE_VOLUME_REPEAT_MS = 150L
    }
}
