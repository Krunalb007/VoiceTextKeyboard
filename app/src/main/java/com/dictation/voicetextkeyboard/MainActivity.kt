package com.dictation.voicetextkeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            setResult(RESULT_OK)
            finish()
        } else {
            handleDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when {
            hasMicPermission() -> {
                setResult(RESULT_OK)
                finish()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                showRationaleDialog()
            }
            else -> {
                // First-time request or "Don't ask again" previously selected.
                requestPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        when {
            hasMicPermission() -> {
                setResult(RESULT_OK)
                finish()
            }
            else ->{
                handleDenied()
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun showRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Microphone access needed")
            .setMessage("Voice typing needs access to your microphone to capture speech.")
            .setPositiveButton("Continue") { d, _ ->
                d.dismiss()
                requestPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton("Cancel") { d, _ ->
                d.dismiss()
                setResult(RESULT_CANCELED)
            }
            .setCancelable(false)
            .show()
    }

    private fun handleDenied() {
        // If rationale is not shown, it's likely permanently denied
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setTitle("Permission required")
                .setMessage("Microphone permission is permanently denied. Enable it in Settings to use voice typing.")
                .setPositiveButton("Open Settings") { d, _ ->
                    d.dismiss()
                    openAppSettings()
                }
                .setNegativeButton("Cancel") { d, _ ->
                    d.dismiss()
                    setResult(RESULT_CANCELED)
                    finish()
                }
                .setCancelable(false)
                .show()
        } else {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }
}
