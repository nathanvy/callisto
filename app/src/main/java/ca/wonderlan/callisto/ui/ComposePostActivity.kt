package ca.wonderlan.callisto.ui

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

        // Store references in a field for use on send
        val prefilledReferences = intent.getStringExtra(EXTRA_REFERENCES)

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
        val fromHeader = when {
            !username.isNullOrBlank() -> username
            else -> "callisto@device" // fallback
        }

        val headers = linkedMapOf(
            "From" to fromHeader,
            "Newsgroups" to newsgroups,
            "Subject" to subject,
            "Date" to dateRfc2822,
            "Message-ID" to msgId
        )

        // If replying, add References header
        intent.getStringExtra(EXTRA_REFERENCES)?.let { ref ->
            if (!ref.isNullOrBlank()) {
                headers["References"] = ref
            }
        }

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    NNTPClient(host, port).use { client ->
                        // Optional AUTH (v1 omitted). If you add AUTHINFO, do it here.
                        // Post text-only body; NNTPClient handles dot-stuffing.
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
