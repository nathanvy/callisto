package ca.wonderlan.callisto.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ca.wonderlan.callisto.R
import ca.wonderlan.callisto.data.Article
import ca.wonderlan.callisto.data.NNTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder


class ArticleListFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private val adapter = ArticleAdapter { article ->
        // Navigate to detail fragment on tap
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                ArticleDetailFragment.newInstance(
                    number = article.number,
                    subject = article.subject,
                    messageId = article.messageId
                )
            )
            .addToBackStack(null)
            .commit()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_article_list, container, false)
        recycler = v.findViewById(R.id.recycler_articles)
        swipe = v.findViewById(R.id.swipe_refresh)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        swipe.setOnRefreshListener { loadArticles() }
        return v
    }

    override fun onResume() {
        super.onResume()
        loadArticles()
    }

    private fun loadArticles() {
        swipe.isRefreshing = true

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val host = prefs.getString("host", "10.0.2.2")!!
        val port = prefs.getString("port", "119")!!.toIntOrNull() ?: 119
        val group = prefs.getString("group", getString(R.string.default_group))!!

        // Step 1: query GROUP to get counts without downloading any articles
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val useTls = prefs.getBoolean("use_tls", false)
                val username = prefs.getString("username", null)
                val password = prefs.getString("password", null)

                val stat = withContext(Dispatchers.IO) {
                    ca.wonderlan.callisto.data.NNTPClient(host, port, useTls).use { client ->
                        if (!username.isNullOrBlank()) client.auth(username, password ?: "")
                        client.selectGroup(group)
                    }
                }

                val lastSeenKey = "last_seen_high:$group"
                val lastSeenHigh = prefs.getLong(lastSeenKey, 0L)
                val newCount = if (stat.high > lastSeenHigh) (stat.high - lastSeenHigh).toInt() else 0

                val threshold = prefs.getString("prompt_threshold", "200")!!.toIntOrNull() ?: 200

                fun actuallyFetch(limit: Int) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val items = withContext(Dispatchers.IO) {
                                ca.wonderlan.callisto.data.NNTPClient(host, port, useTls).use { client ->
                                    if (!username.isNullOrBlank()) client.auth(username, password ?: "")
                                    client.selectGroup(group)
                                    val toFetch = if (limit > 0) limit else 100
                                    client.fetchRecentArticles(toFetch)
                                }
                            }
                            adapter.submit(items)

                            // Update high-water mark if we fetched any new messages
                            if (stat.high > 0L) {
                                prefs.edit().putLong(lastSeenKey, stat.high).apply()
                            }

                            if (items.isEmpty()) {
                                Toast.makeText(requireContext(), "No articles yet in $group", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            swipe.isRefreshing = false
                        }
                    }
                }

                // Step 2: decide whether to prompt
                if (newCount > threshold) {
                    swipe.isRefreshing = false // stop spinner while we prompt
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.dialog_large_download_title))
                        .setMessage(getString(R.string.dialog_large_download_msg, newCount, group))
                        .setPositiveButton(getString(R.string.dialog_download_all, newCount)) { _, _ ->
                            swipe.isRefreshing = true
                            actuallyFetch(newCount)
                        }
                        .setNeutralButton(getString(R.string.dialog_download_cap, threshold)) { _, _ ->
                            swipe.isRefreshing = true
                            actuallyFetch(threshold)
                        }
                        .setNegativeButton(getString(R.string.dialog_cancel)) { _, _ ->
                            // Do nothing; user cancelled
                        }
                        .show()
                } else {
                    // Below threshold: proceed normally
                    actuallyFetch(if (newCount > 0) newCount else 100)
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                swipe.isRefreshing = false
            }
        }
    }


    companion object {
        fun newInstance() = ArticleListFragment()
    }
}

/* ---------------- Adapter & ViewHolder (single copy) ---------------- */

private class ArticleAdapter(
    private val onClick: (Article) -> Unit
) : RecyclerView.Adapter<ArticleVH>() {

    private val data = mutableListOf<Article>()

    fun submit(items: List<Article>) {
        data.clear()
        data.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_article, parent, false)
        return ArticleVH(v, onClick)
    }

    override fun onBindViewHolder(holder: ArticleVH, position: Int) {
        holder.bind(data[position])
    }

    override fun getItemCount(): Int = data.size
}

private class ArticleVH(
    v: View,
    private val onClick: (Article) -> Unit
) : RecyclerView.ViewHolder(v) {

    private val subject = v.findViewById<android.widget.TextView>(R.id.text_subject)
    private val author  = v.findViewById<android.widget.TextView>(R.id.text_author)
    private val preview = v.findViewById<android.widget.TextView>(R.id.text_preview)

    private var current: Article? = null

    init {
        v.setOnClickListener { current?.let(onClick) }
    }

    fun bind(a: Article) {
        current = a
        subject.text = a.subject.ifBlank { "(no subject)" }
        author.text  = a.from
        preview.text = a.body.take(200)
    }
}
