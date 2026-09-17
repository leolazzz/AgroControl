package com.example.agrocontrol.ui.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.agrocontrol.R
import com.example.agrocontrol.data.local.CommandHistoryEntity
import com.example.agrocontrol.utils.CommandDescription
import com.example.agrocontrol.utils.CommandDescriptionBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.json.JSONObject

class EnhancedCommandHistoryAdapter(
    private val descriptionBuilder: CommandDescriptionBuilder
) : ListAdapter<CommandHistoryEntity, EnhancedCommandHistoryAdapter.CommandHistoryViewHolder>(DiffCallback) {

    private var selectedItems = mutableSetOf<Long>()
    private var expandedItems = mutableSetOf<Long>()
    private var selectionModeEnabled = false

    var onItemClickListener: ((CommandHistoryEntity) -> Unit)? = null
    var onItemLongClickListener: ((CommandHistoryEntity) -> Unit)? = null
    var onSelectionChangedListener: (() -> Unit)? = null

    fun setSelectionMode(enabled: Boolean) {
        selectionModeEnabled = enabled
        if (!enabled) {
            selectedItems.clear()
        }
        notifyDataSetChanged()
        onSelectionChangedListener?.invoke()
    }

    fun isInSelectionMode(): Boolean = selectionModeEnabled

    fun getSelectedItems(): Set<Long> = selectedItems.toSet()

    fun toggleSelection(id: Long) {
        if (selectedItems.contains(id)) {
            selectedItems.remove(id)
        } else {
            selectedItems.add(id)
        }
        notifyItemChanged(getItemPosition(id))
        onSelectionChangedListener?.invoke()
    }

    fun selectAll() {
        selectedItems.clear()
        selectedItems.addAll(currentList.map { it.id })
        notifyDataSetChanged()
        onSelectionChangedListener?.invoke()
    }

    fun deselectAll() {
        selectedItems.clear()
        notifyDataSetChanged()
        onSelectionChangedListener?.invoke()
    }

    private fun getItemPosition(id: Long): Int {
        return currentList.indexOfFirst { it.id == id }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommandHistoryViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_command_history, parent, false)
        return CommandHistoryViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: CommandHistoryViewHolder, position: Int) {
        val command = getItem(position)
        holder.bind(command, selectedItems.contains(command.id), expandedItems.contains(command.id))
    }

    inner class CommandHistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCommandIcon: ImageView = itemView.findViewById(R.id.ivCommandIcon)
        private val tvCommandDescription: TextView = itemView.findViewById(R.id.tvCommandDescription)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvResponseSnippet: TextView = itemView.findViewById(R.id.tvResponseSnippet)
        private val tvDetails: TextView = itemView.findViewById(R.id.tvDetails)
        private val tvExpandHint: TextView = itemView.findViewById(R.id.tvExpandHint)
        private val selectionIndicator: ImageView = itemView.findViewById(R.id.selectionIndicator)

        init {
            itemView.setOnClickListener {
                val command = getItem(adapterPosition) ?: return@setOnClickListener
                if (isInSelectionMode()) onItemClickListener?.invoke(command)
                else toggleExpansion(command.id)
            }

            itemView.setOnLongClickListener {
                val command = getItem(adapterPosition) ?: return@setOnLongClickListener false
                onItemLongClickListener?.invoke(command)
                true
            }
        }

        private fun toggleExpansion(id: Long) {
            if (expandedItems.contains(id)) {
                expandedItems.remove(id)
            } else {
                expandedItems.add(id)
            }
            notifyItemChanged(getItemPosition(id))
        }

        fun bind(command: CommandHistoryEntity, isSelected: Boolean, isExpanded: Boolean) {
            itemView.isActivated = isSelected
            val card = itemView as MaterialCardView
            card.strokeWidth = if (isSelected) 3 else 1
            card.strokeColor = MaterialColors.getColor(
                card,
                if (isSelected) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOutlineVariant
            )
            card.setCardBackgroundColor(
                MaterialColors.getColor(
                    card,
                    if (isSelected) com.google.android.material.R.attr.colorPrimaryContainer
                    else com.google.android.material.R.attr.colorSurface
                )
            )
            selectionIndicator.visibility = if (isSelected) View.VISIBLE else View.GONE
            ivCommandIcon.visibility = if (isSelected) View.INVISIBLE else View.VISIBLE

            val commandData = try {
                JSONObject(command.commandData)
            } catch (_: Exception) {
                JSONObject()
            }

            val serverResponse = command.serverResponseRaw?.let {
                try {
                    JSONObject(it)
                } catch (_: Exception) {
                    null
                }
            }

            val description = descriptionBuilder.buildCompleteDescription(
                commandType = command.commandType,
                commandData = commandData,
                status = command.status,
                timestamp = command.timestamp,
                serverResponse = serverResponse,
                aiModelUsed = command.aiModelUsed,
                aiResponseParsed = command.aiResponseParsed,
                aiConfidence = command.aiConfidence,
                serverResponseRaw = command.serverResponseRaw
            )

            ivCommandIcon.setImageResource(description.iconRes)
            tvCommandDescription.text = description.title
            tvStatus.text = getStatusText(command.status)
            tvStatus.setBackgroundResource(getStatusBackground(command.status))
            tvTimestamp.text = getRelativeTimeString(command.timestamp)

            if (description.summary.isNotBlank()) {
                tvResponseSnippet.text = description.summary
                tvResponseSnippet.visibility = View.VISIBLE
            } else {
                tvResponseSnippet.visibility = View.GONE
            }

            tvDetails.text = buildDetailsText(description)
            val showDetails = isExpanded && !selectionModeEnabled
            tvDetails.visibility = if (showDetails) View.VISIBLE else View.GONE
            tvExpandHint.text = when {
                selectionModeEnabled && isSelected -> "Выбрано"
                selectionModeEnabled -> "Нажмите, чтобы выбрать"
                isExpanded -> "Скрыть"
                else -> "Подробнее"
            }
            itemView.contentDescription = if (isExpanded) {
                "${description.title}. Подробности открыты. Нажмите, чтобы скрыть"
            } else {
                "${description.title}. Нажмите, чтобы открыть подробности"
            }
        }

        private fun buildDetailsText(description: CommandDescription): String {
            val commandDetails = description.details.trim().ifBlank {
                "Дополнительные сведения для этого действия не сохранены."
            }
            return listOfNotNull(
                commandDetails,
                description.serverResponseSection?.trim()?.takeIf { it.isNotBlank() }
            ).distinct().joinToString("\n\n").take(1600)
        }

        private fun getStatusText(status: String): String {
            return when (status) {
                "PENDING" -> "Ожидает"
                "SENT_TO_SERVER" -> "Отправлена"
                "SERVER_ACKNOWLEDGED" -> "Принята сервером"
                "QUEUED_FOR_RETRY" -> "В очереди"
                "FAILED" -> "Ошибка"
                "CANCELLED" -> "Отменена"
                else -> status
            }
        }

        private fun getStatusBackground(status: String): Int {
            return when (status) {
                "SERVER_ACKNOWLEDGED" -> R.drawable.status_success
                "FAILED" -> R.drawable.status_error
                else -> R.drawable.status_pending
            }
        }

        private fun getRelativeTimeString(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            val minutes = diff / (1000 * 60)
            val hours = minutes / 60
            val days = hours / 24

            return when {
                minutes < 1 -> "Только что"
                minutes < 60 -> "$minutes мин назад"
                hours < 24 -> "$hours ч назад"
                days < 7 -> "$days дн назад"
                else -> {
                    val date = java.util.Date(timestamp)
                    android.text.format.DateFormat.format("dd.MM.yyyy, HH:mm", date).toString()
                }
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<CommandHistoryEntity>() {
            override fun areItemsTheSame(oldItem: CommandHistoryEntity, newItem: CommandHistoryEntity): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: CommandHistoryEntity, newItem: CommandHistoryEntity): Boolean {
                return oldItem == newItem
            }
        }
    }
}
