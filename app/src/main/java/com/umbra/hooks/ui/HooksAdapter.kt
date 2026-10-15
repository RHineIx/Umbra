package com.umbra.hooks.ui

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.umbra.hooks.R

class HooksAdapter(
    private val hooks: List<HookItem>,
    private val onSettingsClick: (HookItem) -> Unit = {}
) : RecyclerView.Adapter<HooksAdapter.HookViewHolder>() {

    class HookViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvHookTitle: TextView = view.findViewById(R.id.tvHookTitle)
        val tvHookStatus: TextView = view.findViewById(R.id.tvHookStatus)
        val tvHookDesc: TextView = view.findViewById(R.id.tvHookDesc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HookViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_hook_card, parent, false)
        return HookViewHolder(view)
    }

    override fun onBindViewHolder(holder: HookViewHolder, position: Int) {
        val hook = hooks[position]
        val context = holder.itemView.context
        val theme = context.theme
        val typedValue = TypedValue()

        holder.tvHookTitle.setText(hook.titleResId)
        holder.tvHookDesc.setText(hook.descriptionResId)

        holder.tvHookStatus.setText(R.string.status_enabled)
        
        // Dynamic secondary color (Enabled)
        theme.resolveAttribute(com.google.android.material.R.attr.colorSecondaryContainer, typedValue, true)
        holder.tvHookStatus.backgroundTintList = ColorStateList.valueOf(typedValue.data)
        
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSecondaryContainer, typedValue, true)
        holder.tvHookStatus.setTextColor(typedValue.data)
        
        holder.itemView.isClickable = hook.settingsAvailable
        holder.itemView.isFocusable = hook.settingsAvailable
        holder.itemView.setOnClickListener {
            if (hook.settingsAvailable) onSettingsClick(hook)
        }
    }

    override fun getItemCount() = hooks.size
}
