package com.example.taskmanager

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.taskmanager.databinding.ActivityMainBinding
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout // Added for TabLayout
import com.google.android.material.textfield.TextInputEditText
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Calendar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Locale
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var taskAdapter: TaskAdapter
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var db: TaskDatabase
    private lateinit var taskDao: TaskDao
    private val tasks = mutableListOf<Task>()

    private var currentTaskFilter: TaskFilter = TaskFilter.PENDING // To store current filter

    enum class TaskFilter {
        PENDING,
        COMPLETED
    }

    companion object {
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = TaskDatabase.getDatabase(this)
        taskDao = db.taskDao()
        notificationHelper = NotificationHelper(this)

        checkAndRequestPostNotificationPermission()
        checkAndRequestExactAlarmPermission()

        setupRecyclerView()
        setupTabLayout() // Call setup for TabLayout
        setupButtons()
        loadTasksFromDb() // Initial load will use default PENDING filter
    }

    private fun setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTaskFilter = when (tab?.position) {
                    0 -> TaskFilter.PENDING
                    1 -> TaskFilter.COMPLETED
                    else -> TaskFilter.PENDING
                }
                loadTasksFromDb()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        // Ensure the first tab is selected by default if needed (though addOnTabSelectedListener should trigger for the first tab initially)
        // binding.tabLayout.getTabAt(0)?.select()
    }

    private fun loadTasksFromDb() {
        lifecycleScope.launch {
            val dbTasks = withContext(Dispatchers.IO) {
                when (currentTaskFilter) {
                    TaskFilter.PENDING -> taskDao.getPendingTasks()
                    TaskFilter.COMPLETED -> taskDao.getCompletedTasks()
                }
            }
            tasks.clear()
            tasks.addAll(dbTasks)
            taskAdapter.notifyDataSetChanged()
        }
    }

    private fun setupRecyclerView() {
        taskAdapter = TaskAdapter(tasks) { task ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { taskDao.updateTask(task) }
                loadTasksFromDb() // This will refresh the current list based on the active tab
            }
            if (task.isCompleted) {
                Toast.makeText(this, "Task completed: ${task.title}", Toast.LENGTH_SHORT).show()
                notificationHelper.cancelNotification(task)
            } else {
                if (task.scheduledTimeMillis != null) {
                    notificationHelper.scheduleNotification(task)
                }
            }
        }
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = taskAdapter
        }
    }

    private fun setupButtons() {
        binding.btnAddTask.setOnClickListener {
            showAddTaskDialog()
        }
        binding.btnClearCompleted.setOnClickListener {
            lifecycleScope.launch {
                // Only attempt to clear if on the completed tab and there are completed tasks
                if (currentTaskFilter == TaskFilter.COMPLETED && tasks.any { it.isCompleted }) {
                    val completedTasksToClear = tasks.filter { it.isCompleted }
                    completedTasksToClear.forEach { task ->
                        notificationHelper.cancelNotification(task)
                    }
                    withContext(Dispatchers.IO) { taskDao.deleteCompletedTasks() }
                    loadTasksFromDb() // Refresh the list
                    Toast.makeText(this@MainActivity, "Removed ${completedTasksToClear.size} completed tasks", Toast.LENGTH_SHORT).show()
                } else if (currentTaskFilter == TaskFilter.PENDING) {
                    Toast.makeText(this@MainActivity, "Switch to 'Completed' tab to clear tasks", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "No completed tasks to remove on this tab", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.btnResetTasks.setOnClickListener {
            lifecycleScope.launch {
                // Fetch all tasks from DB to ensure we are resetting everything, regardless of current filter
                val allTasksFromDb = withContext(Dispatchers.IO) { taskDao.getAllTasks() }
                allTasksFromDb.forEach { task ->
                    if (task.isCompleted || task.scheduledTimeMillis != null) { // Only update if actually changing something
                        task.isCompleted = false
                        if (task.scheduledTimeMillis != null) {
                            notificationHelper.scheduleNotification(task) // Re-schedule if it had a reminder
                        }
                        withContext(Dispatchers.IO) { taskDao.updateTask(task) }
                    }
                }
                loadTasksFromDb() // Refresh the current tab's view
                Toast.makeText(this@MainActivity, "All tasks have been reset to pending.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAddTaskDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_task, null)
        val titleInput = dialogView.findViewById<TextInputEditText>(R.id.editTextTitle)
        val descriptionInput = dialogView.findViewById<TextInputEditText>(R.id.editTextDescription)
        val reminderCheckBox = dialogView.findViewById<MaterialCheckBox>(R.id.checkBoxSetReminder)
        val reminderLayout = dialogView.findViewById<View>(R.id.reminderLayout)
        val dateButton = dialogView.findViewById<MaterialButton>(R.id.btnDatePicker)
        val timeButton = dialogView.findViewById<MaterialButton>(R.id.btnTimePicker)

        var selectedDate: LocalDateTime? = null

        reminderCheckBox.setOnCheckedChangeListener { _, isChecked ->
            reminderLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) selectedDate = null
        }
        dateButton.setOnClickListener {
            val calendar = Calendar.getInstance()
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    val currentTime = LocalTime.now()
                    selectedDate = LocalDateTime.of(year, month + 1, dayOfMonth, currentTime.hour, currentTime.minute)
                    dateButton.text = getString(R.string.date_format_string, month + 1, dayOfMonth, year)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        timeButton.setOnClickListener {
            val calendar = Calendar.getInstance()
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    selectedDate = selectedDate?.withHour(hourOfDay)?.withMinute(minute)
                        ?: LocalDateTime.now().withHour(hourOfDay).withMinute(minute)
                    timeButton.text = String.format(Locale.getDefault(), getString(R.string.time_format_string), hourOfDay, minute)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true
            ).show()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add New Task")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val title = titleInput.text.toString().trim()
                val description = descriptionInput.text.toString().trim()
                if (title.isNotEmpty()) {
                    val scheduledMillis = if (reminderCheckBox.isChecked && selectedDate != null) {
                        selectedDate!!.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } else null
                    val newTask = Task(
                        title = title,
                        description = description,
                        scheduledTimeMillis = scheduledMillis
                        // isCompleted will be false by default
                    )
                    lifecycleScope.launch {
                        val newTaskId = withContext(Dispatchers.IO) { taskDao.insertTask(newTask) }
                        // Ensure the new task appears on the 'Pending' tab if it's currently selected
                        if (currentTaskFilter == TaskFilter.PENDING) {
                            loadTasksFromDb()
                        } else {
                            // If on 'Completed' tab, new task won't show, which is fine.
                            // Optionally, switch to pending: binding.tabLayout.getTabAt(0)?.select()
                        }
                        if (scheduledMillis != null && newTaskId > 0) {
                            val insertedTask = withContext(Dispatchers.IO) { taskDao.getTaskById(newTaskId) }
                            insertedTask?.let {
                                notificationHelper.scheduleNotification(it)
                                Toast.makeText(this@MainActivity, "Task added with reminder", Toast.LENGTH_SHORT).show()
                            }
                        } else if (newTaskId > 0) {
                            Toast.makeText(this@MainActivity, "Task added successfully", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Please enter a task title", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun checkAndRequestPostNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // Permission is already granted
            } else if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            ) {
                Snackbar.make(
                    binding.root, 
                    "This app needs permission to post notifications for task reminders.",
                    Snackbar.LENGTH_INDEFINITE
                ).setAction("Grant") {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_CODE_POST_NOTIFICATIONS
                    )
                }.show()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun checkAndRequestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle("Exact Alarm Permission Needed")
                    .setMessage("To ensure timely task reminders, this app needs permission to schedule exact alarms. Please grant this permission in the app settings.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        Intent().apply {
                            action = Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                            data = Uri.fromParts("package", packageName, null)
                        }.also {
                            try {
                                startActivity(it)
                            } catch (_: Exception) { 
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                            }
                        }
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()
                        Snackbar.make(binding.root, "Exact alarm permission not granted. Reminders may be less precise.", Snackbar.LENGTH_LONG).show()
                    }
                    .show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Snackbar.make(binding.root, "Notification permission granted.", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Notification permission denied. Task reminders might not work.", Snackbar.LENGTH_LONG).show()
            }
        }
    }
}
