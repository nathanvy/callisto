package ca.wonderlan.callisto.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ca.wonderlan.callisto.R
import androidx.preference.PreferenceManager

class GroupListFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_group_list, container, false)
        val rv = v.findViewById<RecyclerView>(R.id.recycler_groups)
        rv.layoutManager = LinearLayoutManager(requireContext())
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val groups = prefs.getStringSet("subs", setOf(getString(R.string.default_group)))!!.toList()
        rv.adapter = GroupAdapter(groups) { selected ->
            prefs.edit().putString("group", selected).apply()
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        return v
    }

    companion object { fun newInstance() = GroupListFragment() }
}

private class GroupAdapter(
    private val groups: List<String>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<GroupVH>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupVH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
        return GroupVH(tv, onClick)
    }

    override fun getItemCount() = groups.size
    override fun onBindViewHolder(holder: GroupVH, position: Int) = holder.bind(groups[position])
}

private class GroupVH(private val tv: TextView, val onClick: (String) -> Unit) : RecyclerView.ViewHolder(tv) {
    fun bind(name: String) {
        tv.text = name
        tv.setOnClickListener { onClick(name) }
    }
}
