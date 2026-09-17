package com.example.agrocontrol.ui.history

import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.*
import com.example.agrocontrol.R
import com.example.agrocontrol.data.local.CommandHistoryEntity
import com.example.agrocontrol.databinding.ActivityCommandHistoryBinding
import com.example.agrocontrol.utils.CommandDescriptionBuilder
import com.example.agrocontrol.viewmodel.CommandHistoryViewModel
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject

class CommandHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCommandHistoryBinding
    private val viewModel: CommandHistoryViewModel by viewModels()
    private lateinit var adapter: EnhancedCommandHistoryAdapter
    private lateinit var statusCardAdapter: StatusCardAdapter
    private val descriptionBuilder = CommandDescriptionBuilder()
    private var currentFilterStatus: String? = null
    private var currentSearchQuery: String = ""
    private var actionMode: ActionMode? = null
    private var currentSortType = SortType.NEWEST_FIRST
    private var originalList = listOf<CommandHistoryEntity>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommandHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = EnhancedCommandHistoryAdapter(descriptionBuilder)
        statusCardAdapter = StatusCardAdapter()

        setupToolbar()
        setupStatusCards()
        setupRecyclerView()
        setupSwipeToDelete()
        setupAdapterListeners()
        binding.btnSelectEntries.setOnClickListener {
            if (actionMode == null) enterSelectionMode() else actionMode?.finish()
        }
        binding.etHistorySearch.doAfterTextChanged {
            currentSearchQuery = it?.toString().orEmpty().trim().lowercase()
            updateFilteredList()
        }
        observeData()
    }

    private fun setupToolbar() {
        binding.toolbar.title = getString(R.string.command_history_title)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.inflateMenu(R.menu.menu_command_history)
        val primaryColor = com.google.android.material.color.MaterialColors.getColor(
            binding.toolbar,
            com.google.android.material.R.attr.colorPrimary
        )
        for (index in 0 until binding.toolbar.menu.size()) {
            binding.toolbar.menu.getItem(index).icon?.setTint(primaryColor)
        }
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete_all -> {
                    showDeleteAllConfirmation()
                    true
                }

                R.id.action_sort_newest -> {
                    item.isChecked = true
                    setSortType(SortType.NEWEST_FIRST)
                    true
                }

                R.id.action_sort_oldest -> {
                    item.isChecked = true
                    setSortType(SortType.OLDEST_FIRST)
                    true
                }

                else -> false
            }
        }
    }

    private fun setupStatusCards() {
        binding.recyclerStatusCards.apply {
            layoutManager = LinearLayoutManager(this@CommandHistoryActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@CommandHistoryActivity.statusCardAdapter
        }

        statusCardAdapter.setOnStatusClickListener { status ->
            currentFilterStatus = status
            statusCardAdapter.setSelectedStatus(status)
            updateFilteredList()
        }
    }

    private fun updateFilteredList() {
        submitFilteredAndSortedList(originalList)
    }

    private fun setupRecyclerView() {
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@CommandHistoryActivity)
            this.adapter = this@CommandHistoryActivity.adapter
        }
    }

    private fun setupSwipeToDelete() {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val command = adapter.currentList.getOrNull(position) ?: return

                performDeleteWithUndo(listOf(command))
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
    }

    private fun setupAdapterListeners() {
        adapter.onItemClickListener = { command ->
            if (adapter.isInSelectionMode()) {
                adapter.toggleSelection(command.id)
            }
        }

        adapter.onItemLongClickListener = { command ->
            if (!adapter.isInSelectionMode()) {
                enterSelectionMode()
            }
            adapter.toggleSelection(command.id)
            true
        }

        adapter.onSelectionChangedListener = {
            updateActionMode()
        }
    }

    private fun enterSelectionMode() {
        actionMode = startSupportActionMode(actionModeCallback)
        adapter.setSelectionMode(true)
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_selection, menu)
            binding.btnSelectEntries.text = "Готово"
            binding.btnSelectEntries.setIconResource(R.drawable.ic_check)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updateActionModeTitle(mode)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.action_delete -> {
                    val selectedItems = adapter.getSelectedItems().mapNotNull { id ->
                        adapter.currentList.find { it.id == id }
                    }
                    if (selectedItems.isNotEmpty()) {
                        showBulkDeleteConfirmation(selectedItems)
                    }
                    true
                }

                R.id.action_select_all -> {
                    if (adapter.getSelectedItems().size == adapter.itemCount && adapter.itemCount > 0) {
                        adapter.deselectAll()
                    } else {
                        adapter.selectAll()
                    }
                    true
                }

                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            adapter.setSelectionMode(false)
            binding.btnSelectEntries.text = "Выбрать"
            binding.btnSelectEntries.setIconResource(R.drawable.ic_select_entries)
        }
    }

    private fun updateActionMode() {
        actionMode?.let { mode ->
            updateActionModeTitle(mode)
            val hasSelection = adapter.getSelectedItems().isNotEmpty()
            mode.menu?.findItem(R.id.action_delete)?.isEnabled = hasSelection
            mode.menu?.findItem(R.id.action_select_all)?.apply {
                val allSelected = adapter.itemCount > 0 &&
                    adapter.getSelectedItems().size == adapter.itemCount
                title = if (allSelected) "Снять" else "Все"
                contentDescription = if (allSelected) {
                    "Снять выбор со всех записей"
                } else {
                    "Выбрать все записи"
                }
                setIcon(if (allSelected) R.drawable.ic_clear_selection else R.drawable.ic_checklist_all)
                isEnabled = adapter.itemCount > 0
            }
        }
    }

    private fun updateActionModeTitle(mode: ActionMode) {
        val count = adapter.getSelectedItems().size
        mode.title = if (count > 0) "Выбрано: $count" else "Выберите записи"
    }

    private fun performDeleteWithUndo(commands: List<CommandHistoryEntity>) {
        commands.forEach { command ->
            viewModel.softDeleteCommand(command.id, "user")
        }

        Snackbar.make(binding.root, "Удалено записей: ${commands.size}", Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                commands.forEach { command -> viewModel.restoreCommand(command.id) }
            }
            .show()
    }

    private fun showBulkDeleteConfirmation(commands: List<CommandHistoryEntity>) {
        val message = if (commands.size > 5) {
            "Удалить ${commands.size} записей?"
        } else {
            "Удалить записей: ${commands.size}?"
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Удаление записей")
            .setMessage(message)
            .setPositiveButton("Удалить") { _, _ ->
                performDeleteWithUndo(commands)
                actionMode?.finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDeleteAllConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Очистить историю")
            .setMessage("Удалить всю историю команд? Это действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ ->
                viewModel.deleteAllCommands()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun observeData() {
        viewModel.allCommands.observe(this) { commands ->
            submitFilteredAndSortedList(commands)
        }
    }

    private fun submitFilteredAndSortedList(commands: List<CommandHistoryEntity>) {
        originalList = commands
        var list = commands
        if (currentFilterStatus != null) {
            list = list.filter { command ->
                when (currentFilterStatus) {
                    "PENDING_GROUP" -> command.status in setOf("PENDING", "SENT_TO_SERVER", "QUEUED_FOR_RETRY")
                    "FAILED_GROUP" -> command.status in setOf("FAILED", "CANCELLED")
                    else -> command.status == currentFilterStatus
                }
            }
        }
        if (currentSearchQuery.isNotBlank()) {
            list = list.filter { command -> matchesUsefulSearch(command, currentSearchQuery) }
        }
        val sortedList = sortCommands(list)
        adapter.submitList(sortedList)
        statusCardAdapter.updateCounts(commands)
        binding.tvJournalSummary.text = when {
            commands.isEmpty() -> "Журнал пока пуст"
            sortedList.size == commands.size -> "Всего действий: ${commands.size}"
            else -> "Показано ${sortedList.size} из ${commands.size}"
        }
        updateEmptyState(sortedList.isEmpty())
    }

    private fun matchesUsefulSearch(command: CommandHistoryEntity, query: String): Boolean {
        val commandData = try {
            JSONObject(command.commandData)
        } catch (_: Exception) {
            JSONObject()
        }
        val serverResponse = command.serverResponseRaw?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
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
        val aliases = when {
            command.commandType.contains("RELAY") || command.commandType.contains("CONTROL") ->
                "управление реле оборудование освещение полив вентиляция"
            command.commandType.contains("PHOTO") ->
                "фото фотография снимок анализ заболевание болезнь"
            command.commandType.contains("RECOMMENDATION") || command.commandType.contains("AI") ->
                "рекомендация совет агроном растение культура"
            command.commandType.contains("DIAGNOSTICS") || command.commandType.contains("SENSOR") ->
                "датчики показания диагностика температура влажность свет уровень"
            else -> "системное действие соединение"
        }
        val statusWords = when (command.status) {
            "SERVER_ACKNOWLEDGED" -> "выполнено успешно принято"
            "FAILED" -> "ошибка не выполнено"
            "PENDING", "SENT_TO_SERVER", "QUEUED_FOR_RETRY" -> "ожидает очередь отправлено"
            "CANCELLED" -> "отменено"
            else -> command.status
        }
        val dataValues = buildString {
            val keys = commandData.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                append(' ').append(commandData.opt(key)?.toString().orEmpty())
            }
        }
        val searchableText = listOfNotNull(
            description.title,
            description.summary,
            description.details,
            description.serverResponseSection,
            command.detailedDescription,
            command.aiResponseParsed,
            aliases,
            statusWords,
            dataValues
        ).joinToString(" ").lowercase().replace('ё', 'е')
        val terms = query.lowercase().replace('ё', 'е')
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
        return terms.isNotEmpty() && terms.all(searchableText::contains)
    }

    private fun setSortType(sortType: SortType) {
        currentSortType = sortType
        submitFilteredAndSortedList(originalList)
    }

    private fun sortCommands(commands: List<CommandHistoryEntity>): List<CommandHistoryEntity> {
        return when (currentSortType) {
            SortType.NEWEST_FIRST -> commands.sortedByDescending { it.timestamp }
            SortType.OLDEST_FIRST -> commands.sortedBy { it.timestamp }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyStateView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    class StatusCardAdapter : RecyclerView.Adapter<StatusCardAdapter.StatusCardViewHolder>() {

        private val statusItems = listOf(
            StatusItem(null, "Все"),
            StatusItem("PENDING_GROUP", "Ожидают"),
            StatusItem("SERVER_ACKNOWLEDGED", "Приняты"),
            StatusItem("FAILED_GROUP", "Ошибки")
        )
        private var clickListener: ((String?) -> Unit)? = null
        private var selectedStatus: String? = null

        fun updateCounts(commands: List<CommandHistoryEntity>) {
            statusItems.forEach { item ->
                item.count = when (item.status) {
                    null -> commands.size
                    "PENDING_GROUP" -> commands.count { it.status in setOf("PENDING", "SENT_TO_SERVER", "QUEUED_FOR_RETRY") }
                    "FAILED_GROUP" -> commands.count { it.status in setOf("FAILED", "CANCELLED") }
                    else -> commands.count { it.status == item.status }
                }
            }
            notifyDataSetChanged()
        }

        fun setSelectedStatus(status: String?) {
            selectedStatus = status
            notifyDataSetChanged()
        }

        fun setOnStatusClickListener(listener: (String?) -> Unit) {
            clickListener = listener
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StatusCardViewHolder {
            val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_status_card, parent, false)
            return StatusCardViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: StatusCardViewHolder, position: Int) {
            holder.bind(statusItems[position], statusItems[position].status == selectedStatus, clickListener)
        }

        override fun getItemCount() = statusItems.size

        inner class StatusCardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvCount: TextView = itemView.findViewById(R.id.tvCount)
            private val tvLabel: TextView = itemView.findViewById(R.id.tvLabel)

            fun bind(item: StatusItem, selected: Boolean, clickListener: ((String?) -> Unit)?) {
                tvCount.text = item.count.toString()
                tvLabel.text = item.label
                val card = itemView as com.google.android.material.card.MaterialCardView
                card.strokeWidth = if (selected) 2 else 1
                card.strokeColor = com.google.android.material.color.MaterialColors.getColor(
                    card,
                    if (selected) com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOutlineVariant
                )
                card.setCardBackgroundColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        card,
                        if (selected) com.google.android.material.R.attr.colorPrimaryContainer
                        else com.google.android.material.R.attr.colorSurface
                    )
                )
                tvLabel.setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        card,
                        if (selected) com.google.android.material.R.attr.colorOnPrimaryContainer
                        else com.google.android.material.R.attr.colorOnSurface
                    )
                )
                tvCount.setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        card,
                        if (selected) com.google.android.material.R.attr.colorOnPrimaryContainer
                        else com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                itemView.setOnClickListener { clickListener?.invoke(item.status) }
            }
        }
    }

    enum class SortType {
        NEWEST_FIRST,
        OLDEST_FIRST
    }

    data class StatusItem(val status: String?, val label: String, var count: Int = 0)
}
