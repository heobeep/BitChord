package com.music.bitchord

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.music.bitchord.data.sources.mornye.MornyeSessionHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives Mornye's signed-session grant deep link after browser verification.
 *
 * The grant is exchanged on an IO dispatcher and the state is checked against
 * BitChord's encrypted pending request before any session is stored.
 */
class MornyeAuthCallbackActivity : Activity() {
    private var handling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallback(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent)
    }

    private fun handleCallback(intent: Intent?) {
        if (handling) return
        handling = true
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = runCatching {
                MornyeSessionHost.completePendingCallback(applicationContext, uri)
            }
            withContext(Dispatchers.Main) {
                val message = result.fold(
                    onSuccess = { name ->
                        "Verification complete for " + name + ". Return to BitChord and retry your search."
                    },
                    onFailure = { error ->
                        "Verification could not be completed: " + (error.message ?: "unknown error")
                    },
                )
                Toast.makeText(this@MornyeAuthCallbackActivity, message, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(this@MornyeAuthCallbackActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                )
                finish()
            }
        }
    }
}
