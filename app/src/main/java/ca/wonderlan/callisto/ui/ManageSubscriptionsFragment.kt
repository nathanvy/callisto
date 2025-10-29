package ca.wonderlan.callisto.ui

import android.os.Bundle
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ca.wonderlan.callisto.R
import ca.wonderlan.callisto.DrawerMenuHost
import ca.wonderlan.callisto.data.GroupInfo
import ca.wonderlan.callisto.data.NNTPClient
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.widget.doOnTextChanged
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay


class ManageSubscriptionsFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var inputFilter: TextInputEditText

    private var filterJob: Job? = null
    private val MAX_RENDER = 500 // cap rows rendered for smoothness; change as you like


    // Full, cached list from the server (not re-fetched on typing)
    private var allItems: List<GroupChoiceItem> = emptyList()

    // Adapter shows the filtered subset
    private val adapter = GroupChoiceAdapter(
        onToggle = { group, checked -> onToggleSubscribe(group, checked) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_manage_subscriptions, container, false)
        recycler = v.findViewById(R.id.recycler_subs)
        swipe = v.findViewById(R.id.swipe_refresh)
        inputFilter = v.findViewById(R.id.input_filter)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        swipe.setOnRefreshListener { loadFromServer() }

        // Client-side filter only — does not trigger network
        inputFilter.doOnTextChanged { text, _, _, _ ->
            // Debounce keystrokes so we don’t re-filter on every single character
            filterJob?.cancel()
            filterJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(160) // ~1 frame at 6Hz typing; tweak if you want faster/slower
                applyFilter(text?.toString().orEmpty())
            }
        }

        return v
    }

    override fun onResume() {
        super.onResume()
        if (allItems.isEmpty()) {
            loadFromServer()
        } else {
            // Re-apply filter in case prefs changed outside (e.g., drawer)
            applyFilter(inputFilter.text?.toString().orEmpty())
        }
    }

    private fun loadFromServer() {
        swipe.isRefreshing = true
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val host = prefs.getString("host", "10.0.2.2")!!
        val port = prefs.getString("port", "119")!!.toIntOrNull() ?: 119
        val useTls = prefs.getBoolean("use_tls", false)
        val username = prefs.getString("username", null)
        val password = prefs.getString("password", null)

        // read subscribed set once; used for check state
        val subscribed = prefs.getStringSet("subs", emptySet())!!.toMutableSet()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val groups: List<GroupInfo> = withContext(Dispatchers.IO) {
                    NNTPClient(host, port, useTls).use { client ->
                        if (!username.isNullOrBlank()) client.auth(username, password ?: "")
                        client.listGroups()
                    }
                }
                // Cache the full list (sorted once)
                allItems = groups
                    .asSequence()
                    .map { gi -> gi.name }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                    .map { name -> GroupChoiceItem(name, subscribed.contains(name)) }
                    .toList()

                applyFilter(inputFilter.text?.toString().orEmpty())

                if (allItems.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.msg_no_subs), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to load groups: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun applyFilter(query: String) {
        val base = allItems
        if (base.isEmpty()) {
            adapter.submitList(emptyList())
            view?.findViewById<TextView>(R.id.text_count)?.text =
                getString(R.string.groups_count, 0, 0)
            return
        }

        // Snapshot query, filter off the main thread
        val q = query.trim()

        viewLifecycleOwner.lifecycleScope.launch {
            val (filtered, total) = withContext(Dispatchers.Default) {
                if (q.isEmpty()) {
                    // No filter: don’t render all 114k; cap for performance
                    val totalCount = base.size
                    val slice = if (totalCount > MAX_RENDER) base.take(MAX_RENDER) else base.toList()
                    slice to totalCount
                } else {
                    val tokens = q.lowercase(Locale.ROOT)
                        .split(' ')
                        .filter { it.isNotBlank() }

                    // Filter in background, then cap the rendered list
                    val matches = base.asSequence().filter { item ->
                        val name = item.name.lowercase(Locale.ROOT)
                        tokens.all { t -> name.contains(t) || name.startsWith("$t.") }
                    }.toList()

                    val slice = if (matches.size > MAX_RENDER) matches.take(MAX_RENDER) else matches
                    slice to matches.size
                }
            }

            // Always hand ListAdapter a NEW list instance
            adapter.submitList(filtered.toList()) {
                // Optionally scroll to top so the user sees the first matches
                recycler.scrollToPosition(0)
            }

            // Update the counter: showing N of M (filtered matches of total cached)
            val totalBase = if (q.isEmpty()) base.size else total
            view?.findViewById<TextView>(R.id.text_count)?.text =
                getString(R.string.groups_count, total.coerceAtMost(MAX_RENDER), totalBase)
        }
    }



    private fun onToggleSubscribe(group: String, checked: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val set = prefs.getStringSet("subs", emptySet())!!.toMutableSet()
        val wasCurrent = prefs.getString("group", null) == group

        if (checked) set.add(group) else set.remove(group)
        prefs.edit().putStringSet("subs", set).apply()

        // If first-ever sub and no current group, set current
        if (checked && prefs.getString("group", null).isNullOrBlank()) {
            prefs.edit().putString("group", group).apply()
        }
        // If we unsubbed the current group, move current to any remaining
        if (!checked && wasCurrent) {
            val replacement = set.firstOrNull()
            if (replacement != null) {
                prefs.edit().putString("group", replacement).apply()
                Toast.makeText(requireContext(), "Now viewing $replacement", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().remove("group").apply()
                Toast.makeText(requireContext(), "No subscriptions selected", Toast.LENGTH_SHORT).show()
            }
        }

        // Update cached list's check state (so filter reuse stays consistent)
        allItems = allItems.map {
            if (it.name == group) it.copy(subscribed = checked) else it
        }
        applyFilter(inputFilter.text?.toString().orEmpty())

        // Rebuild drawer
        (activity as? DrawerMenuHost)?.rebuildDrawerMenu()
    }

    companion object { fun newInstance() = ManageSubscriptionsFragment() }
}

/* ---------- Adapter (ListAdapter + DiffUtil) ---------- */

private data class GroupChoiceItem(val name: String, val subscribed: Boolean)

private object GroupChoiceDiff : DiffUtil.ItemCallback<GroupChoiceItem>() {
    override fun areItemsTheSame(oldItem: GroupChoiceItem, newItem: GroupChoiceItem) =
        oldItem.name == newItem.name
    override fun areContentsTheSame(oldItem: GroupChoiceItem, newItem: GroupChoiceItem) =
        oldItem == newItem
}

private class GroupChoiceAdapter(
    private val onToggle: (String, Boolean) -> Unit
) : ListAdapter<GroupChoiceItem, GroupChoiceVH>(GroupChoiceDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupChoiceVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_group_choice, parent, false)
        return GroupChoiceVH(v, onToggle)
    }

    override fun onBindViewHolder(holder: GroupChoiceVH, position: Int) {
        holder.bind(getItem(position))
    }
}

private class GroupChoiceVH(
    v: View,
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.ViewHolder(v) {
    private val checkbox = v.findViewById<CheckBox>(R.id.check_subscribed)
    private val label = v.findViewById<TextView>(R.id.text_group)
    private var currentName: String? = null

    fun bind(item: GroupChoiceItem) {
        currentName = item.name
        label.text = item.name

        checkbox.setOnCheckedChangeListener(null)
        checkbox.isChecked = item.subscribed
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            currentName?.let { onToggle(it, isChecked) }
        }
        itemView.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }
    }
}
