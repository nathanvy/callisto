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

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    NNTPClient(host, port).use { client ->
                        client.selectGroup(group)
                        client.fetchRecentArticles(100)
                    }
                }
                if (items.isEmpty()) {
                    Toast.makeText(requireContext(), "No articles yet in $group", Toast.LENGTH_SHORT).show()
                }
                adapter.submit(items)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
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
