package com.umbra.hooks.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.umbra.hooks.R

class HooksAdapter(
    private val hooks: List<HookItem>,
    private val onSettingsClick: (HookItem) -> Unit = {}
) : RecyclerView.Adapter<HooksAdapter.HookViewHolder>() {

    class HookViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvHookTitle: TextView = view.findViewById(R.id.tvHookTitle)
        val ivCustomize: ImageView = view.findViewById(R.id.ivCustomize)
        val tvHookDesc: TextView = view.findViewById(R.id.tvHookDesc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HookViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_hook_card, parent, false)
        return HookViewHolder(view)
    }

    override fun onBindViewHolder(holder: HookViewHolder, position: Int) {
        val hook = hooks[position]
        val context = holder.itemView.context

        holder.tvHookTitle.setText(hook.titleResId)
        holder.tvHookDesc.setText(hook.descriptionResId)
        holder.ivCustomize.visibility = if (hook.settingsAvailable) View.VISIBLE else View.GONE
        holder.ivCustomize.contentDescription = if (hook.settingsAvailable) {
            context.getString(R.string.gboard_customize_hint)
        } else {
            null
        }
        
        holder.itemView.isClickable = hook.settingsAvailable
        holder.itemView.isFocusable = hook.settingsAvailable
        holder.itemView.setOnClickListener {
            if (hook.settingsAvailable) onSettingsClick(hook)
        }
    }

    override fun getItemCount() = hooks.size
}
