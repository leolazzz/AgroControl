package com.example.agrocontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.agrocontrol.auth.LoginActivity
import com.example.agrocontrol.auth.TokenManager
import com.example.agrocontrol.data.local.AppDatabase
import com.example.agrocontrol.data.local.CommandQueueEntity
import com.example.agrocontrol.data.local.SensorDataEntity
import com.example.agrocontrol.databinding.ActivityMainBinding
import com.example.agrocontrol.model.ControlData
import com.example.agrocontrol.model.DiagnosticsData
import com.example.agrocontrol.model.RecResponse
import com.example.agrocontrol.model.RecommendationsRequest
import com.example.agrocontrol.network.RetrofitClient
import com.example.agrocontrol.ui.history.CommandHistoryActivity
import com.example.agrocontrol.utils.ResponseFormatter
import com.example.agrocontrol.viewmodel.CommandHistoryViewModel
import com.google.gson.Gson
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private companion object {
        const val DIAGNOSTICS_TIMEOUT_MS = 5_000L
        const val PHOTO_FLOW_TIMEOUT_MS = 10_000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var tokenManager: TokenManager
    private lateinit var commandHistoryViewModel: CommandHistoryViewModel

    private var currentPhotoPath: String? = null
    private var selectedPhotoFile: File? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openCamera()
        } else {
            Toast.makeText(this, "Разрешение на камеру необходимо", Toast.LENGTH_LONG).show()
        }
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && currentPhotoPath != null) {
            selectPhoto(File(currentPhotoPath!!))
        } else {
            Toast.makeText(this, "Не удалось сделать фото", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadPhotoFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)

        tokenManager = TokenManager(this)
        commandHistoryViewModel = CommandHistoryViewModel(application)
        if (!tokenManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyControlState(AppPrefs.getControlState(this))
        binding.etPlant.setText(AppPrefs.getLastPlant(this))
        binding.etStage.setText(AppPrefs.getLastStage(this))
        binding.tvUserName.text = tokenManager.getUserInfo()?.let { "Пользователь: ${it.username}" }
            ?: "Локальный пользователь"

        binding.btnSendControl.setOnClickListener { sendControl() }
        binding.btnRefreshDiagnostics.setOnClickListener { loadDiagnostics() }
        binding.btnGetAdvice.setOnClickListener { getAdvice() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnLogout.setOnClickListener { logout() }
        binding.fabCommandHistory.setOnClickListener {
            startActivity(Intent(this, CommandHistoryActivity::class.java))
        }

        binding.analysisModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val photoMode = checkedId == R.id.btnModePhoto
                binding.panelAdvice.visibility = if (photoMode) View.GONE else View.VISIBLE
                binding.panelPhoto.visibility = if (photoMode) View.VISIBLE else View.GONE
            }
        }
        binding.switchIncludeSensorsAdvice.setOnCheckedChangeListener { _, _ -> updatePayloadSummaries() }
        binding.switchIncludeSensorsPhoto.setOnCheckedChangeListener { _, _ -> updatePayloadSummaries() }
        binding.btnAnalyzePhoto.setOnClickListener {
            selectedPhotoFile?.let { uploadPhoto(it) }
                ?: Toast.makeText(this, "Сначала выберите фотографию", Toast.LENGTH_SHORT).show()
        }

        binding.btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                openCamera()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnGallery.setOnClickListener { openGallery() }

        updatePayloadSummaries()

        loadDiagnostics()
    }

    private fun openCamera() {
        try {
            val photoFile = File.createTempFile(
                "plant_photo_${System.currentTimeMillis()}",
                ".jpg",
                externalCacheDir
            ).apply {
                currentPhotoPath = absolutePath
            }

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                photoFile
            )
            takePicture.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun openGallery() {
        pickImage.launch("image/*")
    }

    private fun sendControl() {
        binding.btnSendControl.isEnabled = false

        val control = ControlData(
            mode = if (binding.switchMode.isChecked) "пасмурно" else "ясно",
            relay1 = binding.switchRelay1.isChecked,
            relay2 = binding.switchRelay2.isChecked,
            relay3 = binding.switchRelay3.isChecked,
            ec_onoff = binding.switchEC.isChecked,
            ph_onoff = binding.switchPH.isChecked
        )

        lifecycleScope.launch {
            val commandDataJson = JSONObject().apply {
                put("mode", control.mode)
                put("relay1", control.relay1)
                put("relay2", control.relay2)
                put("relay3", control.relay3)
                put("ec_sensor_active", control.ec_onoff)
                put("ph_sensor_active", control.ph_onoff)
            }
            val historyId = commandHistoryViewModel.logCommand(
                commandType = "RELAY_CONTROL",
                commandData = commandDataJson.toString()
            )

            try {
                val response = RetrofitClient.api(this@MainActivity).sendControl(control)
                if (response.isSuccessful) {
                    AppPrefs.setControlState(this@MainActivity, control)
                    commandHistoryViewModel.updateCommandStatus(historyId, "SERVER_ACKNOWLEDGED")
                    Toast.makeText(this@MainActivity, "Команды отправлены", Toast.LENGTH_SHORT).show()
                } else {
                    commandHistoryViewModel.updateCommandStatus(historyId, "FAILED", "HTTP ${response.code()}")
                    if (response.code() >= 500) {
                        AppPrefs.setControlState(this@MainActivity, control)
                        queueControlCommands(control)
                        Toast.makeText(this@MainActivity, "Контроллер недоступен. Команды сохранены в очереди.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Ошибка управления: HTTP ${response.code()}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                AppPrefs.setControlState(this@MainActivity, control)
                commandHistoryViewModel.updateCommandStatus(historyId, "FAILED", e.message)
                queueControlCommands(control)
                Toast.makeText(this@MainActivity, "Нет сети. Команды сохранены в очереди.", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnSendControl.isEnabled = true
            }
        }
    }

    private fun loadDiagnostics() {
        binding.btnRefreshDiagnostics.isEnabled = false

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            val historyId = commandHistoryViewModel.logCommand(
                commandType = "DIAGNOSTICS_FETCH",
                commandData = "{}"
            )

            try {
                binding.tvDiagnostics.text = "Загрузка диагностики..."
                val response = withTimeout(DIAGNOSTICS_TIMEOUT_MS) {
                    RetrofitClient.api(this@MainActivity).getDiagnostics()
                }
                val responseTime = System.currentTimeMillis() - startTime

                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    binding.tvDiagnostics.text = """
                        Освещённость: ${data.light}
                        Влажность воздуха: ${data.humidity}
                        Температура воздуха: ${data.air_temp}
                        Температура раствора: ${data.solution_temp}
                        Уровень раствора: ${data.level}
                        EC: ${data.ec}
                        pH: ${data.ph}
                        Режим: ${data.mode ?: "неизвестно"}
                    """.trimIndent()

                    if (data.telemetry_complete) saveDiagnostics(data)
                    binding.tvConnectionStatus.text = when {
                        data.telemetry_source == "emulated" -> "● Демонстрация: показания сгенерированы"
                        !data.telemetry_complete -> "● Сервер подключён, нет свежих данных контроллера"
                        else -> "● Получены данные контроллера"
                    }
                    val serverControl = ControlData(
                        mode = data.mode,
                        relay1 = data.relay1,
                        relay2 = data.relay2,
                        relay3 = data.relay3,
                        ec_onoff = data.ec_onoff,
                        ph_onoff = data.ph_onoff
                    )
                    applyControlState(serverControl)
                    AppPrefs.setControlState(this@MainActivity, serverControl)

                    commandHistoryViewModel.updateCommandStatus(historyId, "SERVER_ACKNOWLEDGED")
                    val diagnosticsResponse =
                        ResponseFormatter.formatDiagnosticsResponse(data, responseTime, response.code())
                    commandHistoryViewModel.updateServerResponse(
                        id = historyId,
                        serverResponseRaw = diagnosticsResponse.toString(),
                        serverResponseType = "diagnostics",
                        responseTimestamp = System.currentTimeMillis(),
                        responseTimeMs = responseTime,
                        responseStatusCode = response.code(),
                        detailedDescription = null
                    )
                } else {
                    binding.tvConnectionStatus.text = "● Ошибка сервера"
                    binding.tvDiagnostics.text = "Ошибка загрузки диагностики"
                    commandHistoryViewModel.updateCommandStatus(historyId, "FAILED", "HTTP ${response.code()}")

                    val responseTime = System.currentTimeMillis() - startTime
                    val errorResponse = ResponseFormatter.formatErrorResponse(
                        errorMessage = "HTTP ${response.code()}",
                        errorType = "http_error",
                        responseTimeMs = responseTime,
                        statusCode = response.code()
                    )
                    commandHistoryViewModel.updateServerResponse(
                        id = historyId,
                        serverResponseRaw = errorResponse.toString(),
                        serverResponseType = "error",
                        responseTimestamp = System.currentTimeMillis(),
                        responseTimeMs = responseTime,
                        responseStatusCode = response.code(),
                        detailedDescription = null
                    )
                }
            } catch (e: Exception) {
                binding.tvConnectionStatus.text = "● Нет связи с сервером"
                val responseTime = System.currentTimeMillis() - startTime
                binding.tvDiagnostics.text = if (e is TimeoutCancellationException) {
                    "Диагностика не получена за 5 секунд. Показаны последние сохранённые данные."
                } else {
                    "Нет связи с сервером. Проверьте подключение и адрес в настройках."
                }
                commandHistoryViewModel.updateCommandStatus(historyId, "FAILED", e.message)

                val errorResponse = ResponseFormatter.formatErrorResponse(
                    errorMessage = e.message ?: "Unknown network error",
                    errorType = "network_error",
                    responseTimeMs = responseTime,
                    statusCode = null
                )
                commandHistoryViewModel.updateServerResponse(
                    id = historyId,
                    serverResponseRaw = errorResponse.toString(),
                    serverResponseType = "error",
                    responseTimestamp = System.currentTimeMillis(),
                    responseTimeMs = responseTime,
                    responseStatusCode = null,
                    detailedDescription = null
                )
                showCachedDiagnostics()
            } finally {
                binding.btnRefreshDiagnostics.isEnabled = true
            }
        }
    }

    private fun getAdvice() {
        binding.btnGetAdvice.isEnabled = false

        val plant = binding.etPlant.text.toString().ifBlank { "томат" }
        val stage = binding.etStage.text.toString().ifBlank { "рост" }
        val includeSensors = binding.switchIncludeSensorsAdvice.isChecked
        AppPrefs.setPlantContext(this, plant, stage)
        updatePayloadSummaries()

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            val historyId = commandHistoryViewModel.logCommand(
                commandType = "SENSOR_BASED_RECOMMENDATION",
                commandData = JSONObject()
                    .put("plant", plant)
                    .put("stage", stage)
                    .put("includeSensors", includeSensors)
                    .toString()
            )

            try {
                val latest = AppDatabase.getDatabase(this@MainActivity).sensorDao().getLatestData()
                val req = RecommendationsRequest(
                    type = "sensor_based",
                    plant = plant,
                    stage = stage,
                    sensor_data = if (includeSensors) latest?.let { sensorContext(it) } else null,
                    include_sensors = includeSensors
                )
                val response = RetrofitClient.api(this@MainActivity).getRecommendations(req)
                val processingTime = System.currentTimeMillis() - startTime
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    showRecommendations(binding.tvAdvice, body)

                    commandHistoryViewModel.updateCommandStatus(historyId, "SERVER_ACKNOWLEDGED")
                    commandHistoryViewModel.updateServerResponse(
                        id = historyId,
                        serverResponseRaw = Gson().toJson(body),
                        serverResponseType = "ai_recommendation",
                        responseTimestamp = System.currentTimeMillis(),
                        responseTimeMs = processingTime,
                        responseStatusCode = response.code(),
                        detailedDescription = "Получены AI рекомендации за ${processingTime}мс"
                    )
                } else {
                    binding.tvAdvice.text = "Не удалось получить рекомендации"
                    commandHistoryViewModel.updateCommandStatus(historyId, "FAILED", "HTTP ${response.code()}")
                    val processingTime = System.currentTimeMillis() - startTime
                    commandHistoryViewModel.updateServerResponse(
                        id = historyId,
                        serverResponseRaw = null,
                        serverResponseType = "error",
                        responseTimestamp = System.currentTimeMillis(),
                        responseTimeMs = processingTime,
                        responseStatusCode = response.code(),
                        detailedDescription = "Ошибка HTTP ${response.code()}"
                    )
                }
            } catch (e: Exception) {
                val processingTime = System.currentTimeMillis() - startTime
                binding.tvAdvice.text = "Нет связи с сервером. Проверьте подключение и адрес в настройках."
                commandHistoryViewModel.updateCommandStatus(historyId, "FAILED", e.message)
                commandHistoryViewModel.updateServerResponse(
                    id = historyId,
                    serverResponseRaw = null,
                    serverResponseType = "error",
                    responseTimestamp = System.currentTimeMillis(),
                    responseTimeMs = processingTime,
                    responseStatusCode = null,
                    detailedDescription = "Нет связи с сервером. Проверьте подключение и адрес в настройках."
                )
            } finally {
                binding.btnGetAdvice.isEnabled = true
            }
        }
    }

    private fun uploadPhoto(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, "Файл не найден", Toast.LENGTH_SHORT).show()
            return
        }

        val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
        val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
        val stage = (binding.etStage.text.toString().ifBlank { "рост" })
            .toRequestBody("text/plain".toMediaTypeOrNull())
        val plantText = binding.etPlant.text?.toString().orEmpty().ifBlank { "томат" }
        val includeSensors = binding.switchIncludeSensorsPhoto.isChecked
        AppPrefs.setPlantContext(this, plantText, binding.etStage.text?.toString().orEmpty().ifBlank { "рост" })
        val plant = plantText.toRequestBody("text/plain".toMediaTypeOrNull())
        val includeSensorsPart = includeSensors.toString().toRequestBody("text/plain".toMediaTypeOrNull())

        binding.btnAnalyzePhoto.isEnabled = false
        binding.tvPhotoAnalysis.text = "Фотография отправляется на анализ…"
        binding.tvPhotoRecommendations.text = "После анализа будут подготовлены рекомендации…"

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            val historyId = commandHistoryViewModel.logCommand(
                commandType = "PHOTO_ANALYSIS",
                commandData = JSONObject()
                    .put("stage", binding.etStage.text.toString())
                    .put("filename", file.name)
                    .put("fileSize", file.length())
                    .put("includeSensors", includeSensors)
                    .toString()
            )

            try {
                val deadline = startTime + PHOTO_FLOW_TIMEOUT_MS
                val response = withTimeout(timeRemaining(deadline)) {
                    RetrofitClient.api(this@MainActivity).uploadPhoto(body, stage, plant, includeSensorsPart)
                }
                val processingTime = System.currentTimeMillis() - startTime
                if (response.isSuccessful && response.body() != null) {
                    val r = response.body()!!
                    val confPct = r.confidence_score * 100.0
                    val photoRejected = r.metadata?.accepted == false || r.disease_name == "unknown"
                    val rejectionMessage = when (r.metadata?.rejection_reason) {
                        "camera_obscured" -> "Камера закрыта или в кадре нет различимого растения. Откройте объектив и снимите лист целиком при рассеянном свете."
                        "image_too_dark" -> "Фотография слишком тёмная. Уберите руку с камеры и снимите лист при хорошем рассеянном свете."
                        "image_too_bright" -> "Фотография пересвечена. Уберите прямой источник света и повторите снимок."
                        "blank_image" -> "На фотографии недостаточно деталей. Поместите лист в кадр и повторите снимок."
                        "image_too_blurry" -> "Фотография получилась размытой. Зафиксируйте камеру и наведите фокус на лист."
                        "out_of_distribution" -> "Модель не распознала подходящее изображение растения. Снимите один лист крупным планом."
                        "low_confidence" -> "Модель не уверена в результате. Повторите снимок листа с другой стороны."
                        else -> "Фотография не подходит для надёжного анализа. Повторите снимок листа крупным планом."
                    }
                    binding.tvPhotoAnalysis.text = if (photoRejected) {
                        "Фото не принято\n$rejectionMessage"
                    } else {
                        "Предварительный результат: ${r.disease_name}\nУверенность: ${"%.1f".format(confPct)}%"
                    }

                    commandHistoryViewModel.updateAiResponse(
                        id = historyId,
                        model = "DINOv3-B ONNX",
                        raw = null,
                        parsed = "${r.disease_name}, ${(r.confidence_score * 100).toInt()}%",
                        confidence = r.confidence_score.toFloat(),
                        time = processingTime,
                        version = null
                    )

                    val stageText = binding.etStage.text?.toString().orEmpty().ifBlank { "рост" }
                    val capturedSensors = if (includeSensors) {
                        r.metadata?.sensors?.mapValues { it.value as Any }
                            ?: AppDatabase.getDatabase(this@MainActivity).sensorDao().getLatestData()
                                ?.let { sensorContext(it) }
                    } else null
                    val req = RecommendationsRequest(
                        type = "photo_analysis",
                        disease_name = r.disease_name,
                        confidence_score = r.confidence_score,
                        sensor_data = capturedSensors,
                        mode = r.metadata?.mode,
                        plant = plantText,
                        stage = stageText,
                        include_sensors = includeSensors
                    )

                    commandHistoryViewModel.updateCommandStatus(
                        historyId,
                        "SERVER_ACKNOWLEDGED",
                        "Disease: ${r.disease_name}, Confidence: ${(r.confidence_score * 100).toInt()}%"
                    )
                    if (photoRejected) {
                        binding.tvPhotoRecommendations.text = "Рекомендации не сформированы: сначала загрузите подходящую фотографию."
                    } else {
                        getRecommendations(req, deadline)
                    }
                } else {
                    binding.tvPhotoAnalysis.text = "Ошибка анализа: ${response.code()}"
                    commandHistoryViewModel.updateCommandStatus(historyId, "FAILED", "HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                val processingTime = System.currentTimeMillis() - startTime
                binding.tvPhotoAnalysis.text = if (e is TimeoutCancellationException) {
                    "Анализ не завершился за 10 секунд. Попробуйте ещё раз."
                } else {
                    "Нет связи с сервером. Проверьте подключение и адрес в настройках."
                }
                commandHistoryViewModel.updateCommandStatus(historyId, "FAILED", e.message)
            } finally {
                binding.btnAnalyzePhoto.isEnabled = selectedPhotoFile != null
            }
        }
    }

    private suspend fun getRecommendations(req: RecommendationsRequest, deadline: Long) {
        val startTime = System.currentTimeMillis()
        val historyId = commandHistoryViewModel.logCommand(
            commandType = "RECOMMENDATION_REQUEST",
            commandData = JSONObject()
                .put("plant", req.plant)
                .put("stage", req.stage)
                .put("disease", req.disease_name)
                .put("confidence", req.confidence_score)
                .toString()
        )

        try {
            binding.tvPhotoRecommendations.text = "Получение рекомендаций..."
            val response = withTimeout(timeRemaining(deadline)) {
                RetrofitClient.api(this@MainActivity).getRecommendations(req)
            }
            val processingTime = System.currentTimeMillis() - startTime

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                showRecommendations(binding.tvPhotoRecommendations, body)

                val modelUsed = if (body.generation?.llm_used == true) {
                    body.generation.model ?: "Ollama"
                } else {
                    "Локальная агрономическая база знаний"
                }
                val aiResponse = ResponseFormatter.formatAIRecommendationResponse(
                    recResponse = body,
                    processingTimeMs = processingTime,
                    inputType = req.type ?: "sensor_based",
                    confidence = req.confidence_score,
                    modelUsed = modelUsed
                )

                commandHistoryViewModel.updateAiResponse(
                    id = historyId,
                    model = modelUsed,
                    raw = aiResponse.toString(),
                    parsed = body.recommendations,
                    confidence = req.confidence_score?.toFloat(),
                    time = processingTime,
                    version = "server-configured"
                )

                commandHistoryViewModel.updateServerResponse(
                    id = historyId,
                    serverResponseRaw = aiResponse.toString(),
                    serverResponseType = "ai_recommendations",
                    responseTimestamp = System.currentTimeMillis(),
                    responseTimeMs = processingTime,
                    responseStatusCode = response.code(),
                    detailedDescription = null
                )

                commandHistoryViewModel.updateCommandStatus(historyId, "SERVER_ACKNOWLEDGED")
            } else {
                binding.tvPhotoRecommendations.text = "Не удалось получить рекомендации"
                commandHistoryViewModel.updateCommandStatus(historyId, "FAILED", "HTTP ${response.code()}")

                val errorResponse = ResponseFormatter.formatErrorResponse(
                    errorMessage = "HTTP ${response.code()}",
                    errorType = "http_error",
                    responseTimeMs = processingTime,
                    statusCode = response.code()
                )
                commandHistoryViewModel.updateServerResponse(
                    id = historyId,
                    serverResponseRaw = errorResponse.toString(),
                    serverResponseType = "error",
                    responseTimestamp = System.currentTimeMillis(),
                    responseTimeMs = processingTime,
                    responseStatusCode = response.code(),
                    detailedDescription = null
                )
            }
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            binding.tvPhotoRecommendations.text = "Не удалось получить рекомендации. Повторите запрос позже."
            commandHistoryViewModel.updateCommandStatus(historyId, "FAILED", e.message)

            val errorResponse = ResponseFormatter.formatErrorResponse(
                errorMessage = e.message ?: "Unknown error",
                errorType = "network_error",
                responseTimeMs = processingTime,
                statusCode = null
            )
            commandHistoryViewModel.updateServerResponse(
                id = historyId,
                serverResponseRaw = errorResponse.toString(),
                serverResponseType = "error",
                responseTimestamp = System.currentTimeMillis(),
                responseTimeMs = processingTime,
                responseStatusCode = null,
                detailedDescription = null
            )
        }
    }

    private fun showRecommendations(view: TextView, response: RecResponse) {
        view.text = response.recommendations
    }

    private fun timeRemaining(deadline: Long): Long =
        (deadline - System.currentTimeMillis()).coerceAtLeast(1L)

    private fun uploadPhotoFromUri(uri: Uri) {
        val file = uriToFile(uri)
        file?.let { selectPhoto(it) }
            ?: Toast.makeText(this, "Не удалось получить файл", Toast.LENGTH_SHORT).show()
    }

    private fun selectPhoto(file: File) {
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Не удалось прочитать фотографию", Toast.LENGTH_SHORT).show()
            return
        }
        selectedPhotoFile = file
        binding.ivPhotoPreview.setImageURI(Uri.fromFile(file))
        binding.ivPhotoPreview.visibility = View.VISIBLE
        binding.tvSelectedPhoto.text = "Выбрано: ${file.name} · ${file.length() / 1024} КБ"
        binding.btnAnalyzePhoto.isEnabled = true
        binding.tvPhotoAnalysis.text = "Фотография готова к отправке"
        binding.tvPhotoRecommendations.text = "Нажмите «Отправить фото на анализ»"
        updatePayloadSummaries()
    }

    private fun updatePayloadSummaries() {
        val adviceParts = mutableListOf("культура", "стадия")
        if (binding.switchIncludeSensorsAdvice.isChecked) adviceParts += "последние показания"
        binding.tvAdvicePayloadSummary.text = "Будут отправлены: ${adviceParts.joinToString(", ")}"

        val photoParts = mutableListOf("фото", "культура", "стадия")
        if (binding.switchIncludeSensorsPhoto.isChecked) photoParts += "показания"
        binding.tvPhotoPayloadSummary.text = "Будут отправлены: ${photoParts.joinToString(", ")}"
    }

    private fun uriToFile(uri: Uri): File? {
        return try {
            val file = File(cacheDir, "temp_plant_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun queueControlCommands(control: ControlData) {
        val dao = AppDatabase.getDatabase(this).commandQueueDao()
        val timestamp = System.currentTimeMillis()
        val commands = listOf(
            CommandQueueEntity(command_type = "SET_MODE", parameter = control.mode, timestamp = timestamp),
            CommandQueueEntity(command_type = "RELAY_TOGGLE", relay_id = 1, target_state = control.relay1, timestamp = timestamp),
            CommandQueueEntity(command_type = "RELAY_TOGGLE", relay_id = 2, target_state = control.relay2, timestamp = timestamp),
            CommandQueueEntity(command_type = "RELAY_TOGGLE", relay_id = 3, target_state = control.relay3, timestamp = timestamp),
            CommandQueueEntity(command_type = "SET_EC_ENABLED", target_state = control.ec_onoff, timestamp = timestamp),
            CommandQueueEntity(command_type = "SET_PH_ENABLED", target_state = control.ph_onoff, timestamp = timestamp)
        )
        commands.forEach { dao.replacePending(it) }
    }

    private suspend fun saveDiagnostics(data: DiagnosticsData) {
        AppDatabase.getDatabase(this).sensorDao().insert(
            SensorDataEntity(
                timestamp = System.currentTimeMillis(),
                temperature = data.air_temp.toFloatOrNull() ?: 0f,
                humidity = data.humidity.toFloatOrNull() ?: 0f,
                ph = data.ph.toFloatOrNull() ?: 0f,
                ec = data.ec.toFloatOrNull() ?: 0f,
                light = data.light.toFloatOrNull()?.toInt() ?: 0,
                water_level = data.level.toFloatOrNull() ?: 0f,
                mode = data.mode ?: "неизвестно",
                is_synced = true
            )
        )
    }

    private fun applyControlState(state: ControlData) {
        state.mode?.let { binding.switchMode.isChecked = it == "пасмурно" }
        state.relay1?.let { binding.switchRelay1.isChecked = it }
        state.relay2?.let { binding.switchRelay2.isChecked = it }
        state.relay3?.let { binding.switchRelay3.isChecked = it }
        state.ec_onoff?.let { binding.switchEC.isChecked = it }
        state.ph_onoff?.let { binding.switchPH.isChecked = it }
    }

    private fun logout() {
        val access = tokenManager.getAccessToken()
        val refresh = tokenManager.getRefreshToken()
        binding.btnLogout.isEnabled = false
        lifecycleScope.launch {
            try {
                if (access != null && refresh != null) {
                    RetrofitClient.createAuthService(this@MainActivity)
                        .logout("Bearer $access", refresh)
                }
            } catch (_: Exception) {
            } finally {
                tokenManager.clearAll()
                startActivity(Intent(this@MainActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }
    }

    private suspend fun showCachedDiagnostics() {
        val cached = AppDatabase.getDatabase(this).sensorDao().getLatestData() ?: return
        binding.tvDiagnostics.text = """
            Нет сети — последние данные (${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(cached.timestamp))})
            Освещённость: ${cached.light}
            Влажность воздуха: ${cached.humidity}
            Температура воздуха: ${cached.temperature}
            Уровень раствора: ${cached.water_level}
            EC: ${cached.ec}
            pH: ${cached.ph}
            Режим: ${cached.mode}
        """.trimIndent()
    }

    private fun sensorContext(data: SensorDataEntity): Map<String, Any> = mapOf(
        "air_temp" to data.temperature,
        "humidity" to data.humidity,
        "solution_temp" to data.temperature,
        "light" to data.light,
        "level" to data.water_level,
        "ec" to data.ec,
        "ph" to data.ph
    )
}
