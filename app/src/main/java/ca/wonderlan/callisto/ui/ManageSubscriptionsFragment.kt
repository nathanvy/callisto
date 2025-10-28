package ca.wonderlan.callisto.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ca.wonderlan.callisto.R
import ca.wonderlan.callisto.DrawerMenuHost
import ca.wonderlan.callisto.data.NNTPClient
import ca.wonderlan.callisto.data.GroupInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManageSubscriptionsFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private val adapter = GroupChoiceAdapter(
        onToggle = { group, checked -> onToggleSubscribe(group, checked) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_manage_subscriptions, container, false)
        recycler = v.findViewById(R.id.recycler_subs)
        swipe = v.findViewById(R.id.swipe_refresh)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        swipe.setOnRefreshListener { loadFromServer() }
        return v
    }

    override fun onResume() {
        super.onResume()
        loadFromServer()
    }

    private fun loadFromServer() {
        swipe.isRefreshing = true
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val host = prefs.getString("host", "10.0.2.2")!!
        val port = prefs.getString("port", "119")!!.toIntOrNull() ?: 119
        val subscribed = prefs.getStringSet("subs", emptySet())?.toMutableSet() ?: mutableSetOf()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val groups: List<GroupInfo> = withContext(Dispatchers.IO) {
                    NNTPClient(host, port).use { it.listGroups() }
                }
                // Sort by name, modest cap can be applied if needed
                val items = groups.sortedBy { it.name }.map { gi ->
                    GroupChoiceItem(gi.name, subscribed.contains(gi.name))
                }
                adapter.submit(items)
                if (items.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.msg_no_subs), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to load groups: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun onToggleSubscribe(group: String, checked: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val set = prefs.getStringSet("subs", emptySet())!!.toMutableSet()
        if (checked) set.add(group) else set.remove(group)
        prefs.edit().putStringSet("subs", set).apply()

        // If we just subscribed and there's no current group, set it
        val current = prefs.getString("group", null)
        if (current.isNullOrBlank() && checked) {
            prefs.edit().putString("group", group).apply()
        }

        // Ask the activity to rebuild the drawer
        (activity as? DrawerMenuHost)?.rebuildDrawerMenu()
    }

    companion object { fun newInstance() = ManageSubscriptionsFragment() }
}

/* ---------- Adapter ---------- */

private data class GroupChoiceItem(val name: String, val subscribed: Boolean)

private class GroupChoiceAdapter(
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<GroupChoiceVH>() {

    private val data = mutableListOf<GroupChoiceItem>()

    fun submit(items: List<GroupChoiceItem>) {
        data.clear()
        data.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupChoiceVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_group_choice, parent, false)
        return GroupChoiceVH(v, onToggle)
    }

    override fun onBindViewHolder(holder: GroupChoiceVH, position: Int) = holder.bind(data[position])
    override fun getItemCount(): Int = data.size
}

private class GroupChoiceVH(
    v: View,
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.ViewHolder(v) {
    private val checkbox = v.findViewById<CheckBox>(R.id.check_subscribed)
    private val label = v.findViewById<TextView>(R.id.text_group)

    fun bind(item: GroupChoiceItem) {
        label.text = item.name
        // Avoid triggering listener while updating UI
        checkbox.setOnCheckedChangeListener(null)
        checkbox.isChecked = item.subscribed
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            onToggle(item.name, isChecked)
        }
        // Clicking row toggles too
        itemView.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }
    }
}
