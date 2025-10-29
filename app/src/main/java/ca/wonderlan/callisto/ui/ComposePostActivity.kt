package ca.wonderlan.callisto.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ca.wonderlan.callisto.R
import ca.wonderlan.callisto.data.NNTPClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder


class ComposePostActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP = "extra_group"
        const val EXTRA_SUBJECT = "extra_subject"
        const val EXTRA_REFERENCES = "extra_references"
    }


    private lateinit var inputGroups: TextInputEditText
    private lateinit var inputSubject: TextInputEditText
    private lateinit var inputBody: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Callisto) // ensure material 3 theme if needed
        setContentView(R.layout.activity_compose_post)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.compose_post)

        inputGroups = findViewById(R.id.input_newsgroups)
        inputSubject = findViewById(R.id.input_subject)
        inputBody = findViewById(R.id.input_body)

        // Prefill subject and (optional) references from intent
        intent.getStringExtra(EXTRA_SUBJECT)?.let { inputSubject.setText(it) }

        // Prefill group from intent or preferences
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val prefillGroup = intent.getStringExtra(EXTRA_GROUP)
            ?: prefs.getString("group", getString(R.string.default_group))
            ?: getString(R.string.default_group)
        inputGroups.setText(prefillGroup)

        findViewById<MaterialButton>(R.id.button_send).setOnClickListener {
            sendPost()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun sendPost() {
        val newsgroups = inputGroups.text?.toString()?.trim().orEmpty()
        val subject = inputSubject.text?.toString()?.trim().orEmpty()
        val bodyText = inputBody.text?.toString() ?: ""

        if (newsgroups.isBlank()) {
            toast("Newsgroups required")
            return
        }
        if (subject.isBlank()) {
            toast("Subject required")
            return
        }
        if (bodyText.isBlank()) {
            toast("Body required")
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val host = prefs.getString("host", "10.0.2.2")!!
        val port = prefs.getString("port", "119")!!.toIntOrNull() ?: 119
        val username = prefs.getString("username", null)
        val password = prefs.getString("password", null)

        // Build minimal headers
        val msgId = "<${UUID.randomUUID()}@callisto>"
        val dateRfc2822 = rfc2822(Date())
        val displayName = prefs.getString("display_name", "")!!.trim()
        val email = prefs.getString("email", "")!!.trim()

        if (displayName.isEmpty() || email.isEmpty()) {
            // Block posting and send the user to Settings
            MaterialAlertDialogBuilder(this)
                .setTitle("Missing identity")
                .setMessage("Please set your Display name and Email in Settings before posting.")
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(this, ca.wonderlan.callisto.MainActivity::class.java)
                        .putExtra("open_settings", true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val fromHeader = "$displayName <$email>"

        val headers = linkedMapOf(
            "From" to fromHeader,
            "Newsgroups" to newsgroups,
            "Subject" to subject,
            "Date" to dateRfc2822,
            "Message-ID" to msgId
        )

        // If replying, add References and In-Reply-To for better threading
        intent.getStringExtra(EXTRA_REFERENCES)?.let { raw ->
            val ref = raw.trim().let { s -> if (s.startsWith("<") && s.endsWith(">")) s else "<$s>" }
            headers["References"] = ref
            headers["In-Reply-To"] = ref
        }

        lifecycleScope.launch {
            val useTls = prefs.getBoolean("use_tls", false)

            val ok = withContext(Dispatchers.IO) {
                try {
                    NNTPClient(host, port, useTls).use { client ->
                        if (!username.isNullOrBlank()) client.auth(username, password ?: "")
                        client.post(headers, bodyText.split("\n"))
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { toast("Post failed: ${e.message}") }
                    false
                }
            }
            if (ok) {
                toast("Posted")
                finish()
            }
        }
    }

    private fun rfc2822(d: Date): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(d)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
