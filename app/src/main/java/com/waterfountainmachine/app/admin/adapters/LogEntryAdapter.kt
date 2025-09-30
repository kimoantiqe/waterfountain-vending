package com.waterfountainmachine.app.admin.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.admin.models.LogEntry
import java.text.SimpleDateFormat
import java.util.*

class LogEntryAdapter : ListAdapter<LogEntry, LogEntryAdapter.LogViewHolder>(LogDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_entry, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timestampText: TextView = itemView.findViewById(R.id.timestampText)
        private val levelText: TextView = itemView.findViewById(R.id.levelText)
        private val tagText: TextView = itemView.findViewById(R.id.tagText)
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        
        private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

        fun bind(logEntry: LogEntry) {
            timestampText.text = dateFormat.format(Date(logEntry.timestamp))
            levelText.text = logEntry.level.name
            tagText.text = logEntry.tag
            messageText.text = logEntry.message

            // Set level color
            val levelColor = when (logEntry.level) {
                LogEntry.Level.ERROR -> ContextCompat.getColor(itemView.context, R.color.admin_error)
                LogEntry.Level.WARNING -> ContextCompat.getColor(itemView.context, R.color.admin_warning)
                LogEntry.Level.INFO -> ContextCompat.getColor(itemView.context, R.color.white_70)
                LogEntry.Level.DEBUG -> ContextCompat.getColor(itemView.context, R.color.white_70)
            }
            levelText.setTextColor(levelColor)
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem == newItem
        }
    }
}
