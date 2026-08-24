package com.kasumi.tool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.kasumi.tool.ui.theme.KasumiTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Hosts the UI and owns the two things that genuinely need an Activity: the
 * package-installer intents and the install-status broadcast.
 *
 * All catalogue state and I/O now live in [AppsViewModel] / [ApkRepository], so
 * this class no longer survives-or-loses the app list across rotation.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppsViewModel by viewModels()

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != "${context.packageName}.INSTALL_COMMIT") return
            val status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE,
            )
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> launchConfirmation(context, intent)
                PackageInstaller.STATUS_SUCCESS -> toast(context, getString(R.string.install_success))
                else -> {
                    val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: getString(R.string.install_failed_unknown, status)
                    toast(context, getString(R.string.install_failed_with_reason, reason))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerInstallReceiver()
        requestStoragePermission()

        setContent {
            KasumiTheme {
                KasumiApp(
                    viewModel = viewModel,
                    onInstallApk = ::installWithSystemInstaller,
                    onInstallSplits = ::installSplitsWithSession,
                )
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(installReceiver)
        super.onDestroy()
    }

    // --- Install-status broadcast --------------------------------------------

    /**
     * Registered NOT_EXPORTED on purpose.
     *
     * The status broadcast is delivered through a PendingIntent this app
     * created, so the system sends it under our own identity and an
     * unexported receiver still gets it. Registering it exported let any
     * installed app fake a status broadcast — and because the handler pulls
     * [Intent.EXTRA_INTENT] out and starts it, that was an arbitrary-intent
     * launch through an unprotected component.
     */
    private fun registerInstallReceiver() {
        ContextCompat.registerReceiver(
            this,
            installReceiver,
            IntentFilter("$packageName.INSTALL_COMMIT"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // Lint cannot see through ContextCompat.registerReceiver, so it still treats
    // this broadcast as untrusted. The receiver above is registered
    // RECEIVER_NOT_EXPORTED and the intent is package-scoped, so the only sender
    // is the PendingIntent this app handed to PackageInstaller.
    @Suppress("UnsafeIntentLaunch")
    private fun launchConfirmation(context: Context, intent: Intent) {
        val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        } ?: return

        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(confirmIntent)
        } catch (e: Exception) {
            toast(context, getString(R.string.install_confirm_dialog_failed, e.message ?: ""))
        }
    }

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    // --- Installer intents ----------------------------------------------------

    /**
     * Hands a single APK to the system package installer.
     *
     * @return whether the caller may drop the request. False means the user was
     *   sent to grant "install unknown apps" first and nothing was installed yet,
     *   so the request has to be kept for a retry once they come back.
     */
    private fun installWithSystemInstaller(file: File, report: (UiText) -> Unit): Boolean {
        if (!ensureCanRequestInstalls(report)) return false

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            // No installer on this ROM: retrying will not help, so the request
            // is finished either way.
            report(UiText.res(R.string.open_installer_failed, e.message ?: ""))
            true
        }
    }

    /**
     * Installs a split package through a PackageInstaller session.
     *
     * @return whether the caller may drop the request; see
     *   [installWithSystemInstaller].
     */
    private suspend fun installSplitsWithSession(files: List<File>, report: (UiText) -> Unit): Boolean {
        if (!ensureCanRequestInstalls(report)) return false

        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        var sessionId = NO_SESSION
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                withContext(Dispatchers.IO) {
                    for (f in files) {
                        FileInputStream(f).use { input ->
                            session.openWrite(f.name, 0, f.length()).use { out ->
                                input.copyTo(out)
                                session.fsync(out)
                            }
                        }
                    }
                }
                val intent = Intent("$packageName.INSTALL_COMMIT").setPackage(packageName)
                val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.app.PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val pi = android.app.PendingIntent.getBroadcast(
                    this,
                    sessionId,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or mutability,
                )
                session.commit(pi.intentSender)
                report(UiText.res(R.string.install_in_progress))
            }
        } catch (e: CancellationException) {
            // The screen left RESUMED while the APKs were still being written, so
            // nothing was committed. Rethrowing keeps the caller from marking the
            // request handled — swallowing it here reported success and dropped
            // the install, because cancellation is cooperative and the
            // acknowledgement that follows is not a suspending call.
            abandonQuietly(installer, sessionId)
            throw e
        } catch (e: Exception) {
            // A session failure is terminal for this attempt; the user can start
            // a new install rather than having it silently retried on resume.
            abandonQuietly(installer, sessionId)
            report(UiText.res(R.string.install_splits_error, e.message ?: ""))
        }
        return true
    }

    /**
     * Drops a session that never reached commit. Sessions outlive the process and
     * an app may only hold so many, so leaking one per interrupted install
     * eventually blocks installing altogether.
     */
    private fun abandonQuietly(installer: PackageInstaller, sessionId: Int) {
        if (sessionId == NO_SESSION) return
        runCatching { installer.abandonSession(sessionId) }
    }

    /**
     * Returns true when the app may install packages; otherwise sends the user to
     * the "install unknown apps" screen and reports why nothing happened.
     */
    private fun ensureCanRequestInstalls(report: (UiText) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (packageManager.canRequestPackageInstalls()) return true

        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:$packageName".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            report(UiText.res(R.string.grant_unknown_sources_hint))
        } catch (e: Exception) {
            report(UiText.res(R.string.open_unknown_sources_failed, e.message ?: ""))
        }
        return false
    }

    // --- Permissions ----------------------------------------------------------

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:$packageName".toUri()
                    }
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    // No settings activity on this ROM; the feature degrades on its own.
                }
            }
        } else {
            // minSdk is 24, so runtime permissions are always available here.
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                REQUEST_STORAGE,
            )
        }
    }

    private companion object {
        const val REQUEST_STORAGE = 100
        const val NO_SESSION = -1
    }
}
