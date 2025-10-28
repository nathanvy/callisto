package ca.wonderlan.callisto.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import ca.wonderlan.callisto.R
import ca.wonderlan.callisto.data.NNTPClient
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArticleDetailFragment : Fragment() {

    companion object {
        private const val ARG_NUMBER = "arg_number"
        private const val ARG_SUBJECT = "arg_subject"
        private const val ARG_MESSAGE_ID = "arg_message_id"

        fun newInstance(number: Long, subject: String?, messageId: String?): ArticleDetailFragment {
            val f = ArticleDetailFragment()
            f.arguments = Bundle().apply {
                putLong(ARG_NUMBER, number)
                putString(ARG_SUBJECT, subject)
                putString(ARG_MESSAGE_ID, messageId)
            }
            return f
        }
    }

    private lateinit var textSubject: TextView
    private lateinit var textFrom: TextView
    private lateinit var textDate: TextView
    private lateinit var textBody: TextView
    private lateinit var buttonReply: MaterialButton

    private var number: Long = 0
    private var subjectHint: String? = null
    private var messageId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        number = requireArguments().getLong(ARG_NUMBER)
        subjectHint = requireArguments().getString(ARG_SUBJECT)
        messageId = requireArguments().getString(ARG_MESSAGE_ID)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_article_detail, container, false)
        textSubject = v.findViewById(R.id.text_subject)
        textFrom = v.findViewById(R.id.text_from)
        textDate = v.findViewById(R.id.text_date)
        textBody = v.findViewById(R.id.text_body)
        buttonReply = v.findViewById(R.id.button_reply)

        // Show placeholders while loading
        textSubject.text = subjectHint ?: getString(R.string.loading)
        textFrom.text = ""
        textDate.text = ""
        textBody.text = ""

        loadArticle()

        buttonReply.setOnClickListener {
            openReply()
        }

        return v
    }

    private fun loadArticle() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val host = prefs.getString("host", "10.0.2.2")!!
        val port = prefs.getString("port", "119")!!.toIntOrNull() ?: 119
        val group = prefs.getString("group", getString(R.string.default_group))!!

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    NNTPClient(host, port).use { client ->
                        client.selectGroup(group)
                        val head = clientHeadSafe(client, number)
                        val body = clientBodySafe(client, number)
                        Triple(head, body, client) // keep message-id from head
                    }
                }
                val head = result.first ?: emptyMap()
                val bodyLines = result.second ?: emptyList()
                messageId = messageId ?: head["message-id"]

                textSubject.text = head["subject"] ?: subjectHint ?: "(no subject)"
                textFrom.text = head["from"] ?: ""
                textDate.text = head["date"] ?: ""
                textBody.text = bodyLines.joinToString("\n")
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to load article: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openReply() {
        val context = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val group = prefs.getString("group", getString(R.string.default_group)) ?: getString(R.string.default_group)
        val subj = textSubject.text?.toString()?.let { s -> if (s.startsWith("Re:", true)) s else "Re: $s" } ?: "Re:"
        val references = messageId // may be null if server omitted

        // Reuse Compose screen
        val intent = android.content.Intent(context, ComposePostActivity::class.java)
            .putExtra(ComposePostActivity.EXTRA_GROUP, group)
            .putExtra(ComposePostActivity.EXTRA_SUBJECT, subj)
            .putExtra(ComposePostActivity.EXTRA_REFERENCES, references)
        startActivity(intent)
    }

    // Helpers tolerate missing numbers (423/430)
    private suspend fun clientHeadSafe(client: NNTPClient, num: Long): Map<String, String>? =
        withContext(Dispatchers.IO) {
            try {
                val m = javaClass.getDeclaredMethod("headShim", NNTPClient::class.java, Long::class.java)
                @Suppress("UNCHECKED_CAST") (m.invoke(this@ArticleDetailFragment, client, num) as Map<String,String>?)
            } catch (_: Exception) {
                // Fallback to using reflection-less access because head() is private — reissue commands here:
                clientHeadCompat(client, num)
            }
        }

    private suspend fun clientBodySafe(client: NNTPClient, num: Long): List<String>? =
        withContext(Dispatchers.IO) {
            try {
                val m = javaClass.getDeclaredMethod("bodyShim", NNTPClient::class.java, Long::class.java)
                @Suppress("UNCHECKED_CAST") (m.invoke(this@ArticleDetailFragment, client, num) as List<String>?)
            } catch (_: Exception) {
                clientBodyCompat(client, num)
            }
        }

    // Compatibility versions (duplicate minimal logic because NNTPClient.head/body are private)
    private fun clientHeadCompat(client: NNTPClient, num: Long): Map<String, String>? {
        val r = client.javaClass.getDeclaredMethod("send", String::class.java)
        r.isAccessible = true
        r.invoke(client, "HEAD $num")
        val readerField = client.javaClass.getDeclaredField("reader").apply { isAccessible = true }
        val br = readerField.get(client) as java.io.BufferedReader
        val first = br.readLine() ?: return null
        if (first.startsWith("221")) {
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = br.readLine() ?: break
                if (line == ".") break
                if (line.isBlank()) continue
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    headers[key] = if (key in headers) headers[key] + " " + value else value
                }
            }
            return headers
        }
        if (first.startsWith("423") || first.startsWith("430")) return null
        throw RuntimeException("HEAD $num failed: $first")
    }

    private fun clientBodyCompat(client: NNTPClient, num: Long): List<String>? {
        val r = client.javaClass.getDeclaredMethod("send", String::class.java)
        r.isAccessible = true
        r.invoke(client, "BODY $num")
        val readerField = client.javaClass.getDeclaredField("reader").apply { isAccessible = true }
        val br = readerField.get(client) as java.io.BufferedReader
        val first = br.readLine() ?: return null
        if (first.startsWith("222")) {
            val lines = mutableListOf<String>()
            while (true) {
                val line = br.readLine() ?: break
                if (line == ".") break
                lines.add(if (line.startsWith("..")) line.drop(1) else line)
            }
            return lines
        }
        if (first.startsWith("423") || first.startsWith("430")) return null
        throw RuntimeException("BODY $num failed: $first")
    }
}
