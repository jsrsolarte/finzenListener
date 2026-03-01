package lat.ramper.finzen.listener

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.*
import kotlinx.coroutines.launch
import lat.ramper.finzen.listener.BuildConfig
import lat.ramper.finzen.listener.data.AppDatabase
import lat.ramper.finzen.listener.data.NotificationEntry
import lat.ramper.finzen.listener.network.UpdateApi
import lat.ramper.finzen.listener.network.UpdateInfo
import lat.ramper.finzen.listener.network.WebhookWorker
import lat.ramper.finzen.listener.service.NotificationListener
import lat.ramper.finzen.listener.ui.theme.FinzenListenerTheme
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FinzenListenerTheme {
                MainScreen()
            }
        }
    }
}

data class AppSelectionInfo(val packageName: String, val label: String, val isSystem: Boolean)

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val sharedPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    
    var userId by remember { mutableStateOf(sharedPrefs.getString("user_id", "") ?: "") }
    var isUserIdLocked by remember { mutableStateOf(userId.isNotEmpty()) }
    
    var selectedApps by remember { 
        mutableStateOf(sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()) 
    }
    
    val db = remember { AppDatabase.getDatabase(context) }
    val notifications by db.notificationDao().getAllNotifications().collectAsState(initial = emptyList())
    
    val installedApps = remember { getInstalledApps(context) }
    var searchQuery by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var hideSystemApps by remember { mutableStateOf(true) }

    var isNotificationServiceEnabled by remember { mutableStateOf(isNotificationServiceEnabled(context)) }
    var isBatteryOptimizationDisabled by remember { mutableStateOf(isBatteryOptimizationDisabled(context)) }

    var editingAppSettings by remember { mutableStateOf<AppSelectionInfo?>(null) }
    var selectedNotificationForMenu by remember { mutableStateOf<NotificationEntry?>(null) }
    
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        val GITHUB_USER = "jsrsolarte"
        val GITHUB_REPO = "finzenListener"
        checkUpdates(GITHUB_USER, GITHUB_REPO) { info ->
            updateInfo = info
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isNotificationServiceEnabled = isNotificationServiceEnabled(context)
                isBatteryOptimizationDisabled = isBatteryOptimizationDisabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    val filteredApps = installedApps.filter { app ->
        val matchesSearch = app.label.contains(searchQuery, ignoreCase = true) || 
                          app.packageName.contains(searchQuery, ignoreCase = true)
        val matchesSystemFilter = !hideSystemApps || !app.isSystem
        matchesSearch && matchesSystemFilter
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(modifier = Modifier.padding(16.dp).statusBarsPadding()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Finzen Listener", style = MaterialTheme.typography.headlineMedium)
                    if (showHistory && notifications.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch { db.notificationDao().deleteAll() }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Limpiar historial", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (!isNotificationServiceEnabled || !isBatteryOptimizationDisabled) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Acción requerida", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            if (!isNotificationServiceEnabled) {
                                Text("• El servicio de escucha no está activo.", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                                    Text("Activar Permiso de Notificaciones")
                                }
                            }
                            if (!isBatteryOptimizationDisabled) {
                                Text("• El ahorro de batería puede detener la app.", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                }) {
                                    Text("Desactivar Optimización de Batería")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = userId,
                        onValueChange = { 
                            userId = it
                            sharedPrefs.edit().putString("user_id", it).apply()
                        },
                        label = { Text("User ID para Webhook") },
                        modifier = Modifier.weight(1f),
                        enabled = !isUserIdLocked,
                        trailingIcon = {
                            if (isUserIdLocked) {
                                Icon(Icons.Default.Lock, contentDescription = "Bloqueado")
                            }
                        }
                    )
                    IconButton(onClick = { isUserIdLocked = !isUserIdLocked }) {
                        Icon(
                            if (isUserIdLocked) Icons.Default.Edit else Icons.Default.Lock,
                            contentDescription = if (isUserIdLocked) "Editar" else "Bloquear",
                            tint = if (isUserIdLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val intent = Intent(context, NotificationListener::class.java).apply {
                                action = NotificationListener.ACTION_PROCESS_ACTIVE
                            }
                            context.startService(intent)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Sincronizar", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = { showHistory = !showHistory },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (showHistory) "Apps" else "Historial", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(horizontal = 16.dp)) {
            if (showHistory) {
                Text("Historial Local:", style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(notifications) { notification ->
                        NotificationItem(
                            notification = notification,
                            onRetry = {
                                scope.launch {
                                    db.notificationDao().markAsUnsent(notification.id)
                                    enqueueWebhookWork(context)
                                }
                            },
                            onLongClick = { selectedNotificationForMenu = notification }
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Buscar App") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hideSystemApps, onCheckedChange = { hideSystemApps = it })
                    Text("Ocultar apps del sistema", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Apps a escuchar:", style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredApps) { app ->
                        AppRow(
                            app = app,
                            isSelected = selectedApps.contains(app.packageName),
                            onCheckedChange = { isChecked ->
                                val current = selectedApps.toMutableSet()
                                if (isChecked) current.add(app.packageName) else current.remove(app.packageName)
                                selectedApps = current
                                sharedPrefs.edit().putStringSet("selected_apps", current).apply()
                            },
                            onSettingsClick = { editingAppSettings = app }
                        )
                    }
                }
            }
        }

        if (editingAppSettings != null) {
            KeywordFilterDialog(
                app = editingAppSettings!!,
                initialKeywords = sharedPrefs.getString("keywords_${editingAppSettings!!.packageName}", "") ?: "",
                onDismiss = { editingAppSettings = null },
                onSave = { keywords ->
                    sharedPrefs.edit().putString("keywords_${editingAppSettings!!.packageName}", keywords).apply()
                    editingAppSettings = null
                }
            )
        }

        if (selectedNotificationForMenu != null) {
            AlertDialog(
                onDismissRequest = { selectedNotificationForMenu = null },
                title = { Text("Opciones de notificación") },
                text = { Text("¿Qué deseas hacer con esta notificación?") },
                confirmButton = {
                    TextButton(onClick = {
                        val original = selectedNotificationForMenu!!
                        scope.launch {
                            val resendEntry = original.copy(
                                id = 0, 
                                isSent = false
                            )
                            db.notificationDao().insert(resendEntry)
                            enqueueWebhookWork(context)
                            selectedNotificationForMenu = null
                        }
                    }) {
                        Text("Reenviar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        val idToDelete = selectedNotificationForMenu!!.id
                        scope.launch {
                            db.notificationDao().deleteById(idToDelete)
                            selectedNotificationForMenu = null
                        }
                    }) {
                        Text("Eliminar", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }

        if (updateInfo != null) {
            AlertDialog(
                onDismissRequest = { updateInfo = null },
                title = { Text("Actualización Disponible v${updateInfo!!.versionName}") },
                text = { Text(updateInfo!!.releaseNotes ?: "Hay una nueva versión disponible para descargar.") },
                confirmButton = {
                    Button(onClick = {
                        downloadAndInstallApk(context, updateInfo!!.apkUrl)
                        updateInfo = null
                    }) {
                        Text("Actualizar Ahora")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updateInfo = null }) {
                        Text("Más tarde")
                    }
                }
            )
        }
    }
}

@Composable
fun KeywordFilterDialog(
    app: AppSelectionInfo,
    initialKeywords: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var keywords by remember { mutableStateOf(initialKeywords) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filtros para ${app.label}") },
        text = {
            Column {
                Text("Solo se enviarán las notificaciones que contengan alguna de estas palabras (separadas por coma).", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    placeholder = { Text("ej: compra, pago, transferencia") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Dejar vacío para enviar todo.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(keywords) }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
fun NotificationItem(notification: NotificationEntry, onRetry: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongClick() }
                )
            },
        colors = CardDefaults.cardColors(
            containerColor = if (notification.isSent) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(notification.appName, style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        notification.formattedDateTime,
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (!notification.isSent) {
                        IconButton(onClick = onRetry, modifier = Modifier.size(24.dp).padding(start = 4.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reintentar", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Text(notification.title ?: "Sin título", style = MaterialTheme.typography.bodyLarge)
            Text(notification.text ?: "", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (notification.isSent) "Enviado exitosamente" else "Pendiente de envío",
                style = MaterialTheme.typography.labelSmall,
                color = if (notification.isSent) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
        }
    }
}

@Composable
fun AppRow(app: AppSelectionInfo, isSelected: Boolean, onCheckedChange: (Boolean) -> Unit, onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = isSelected, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
        }
        if (isSelected) {
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = "Filtros", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

fun getInstalledApps(context: Context): List<AppSelectionInfo> {
    val pm = context.packageManager
    return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .map { 
            val isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || 
                          (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            AppSelectionInfo(it.packageName, pm.getApplicationLabel(it).toString(), isSystem) 
        }
        .sortedBy { it.label }
}

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    if (flat != null) {
        val names = flat.split(":")
        for (name in names) {
            val cn = android.content.ComponentName.unflattenFromString(name)
            if (cn != null && cn.packageName == pkgName) return true
        }
    }
    return false
}

private fun isBatteryOptimizationDisabled(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun enqueueWebhookWork(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val workRequest = OneTimeWorkRequestBuilder<WebhookWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            WorkRequest.MIN_BACKOFF_MILLIS,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        "webhook_sync",
        ExistingWorkPolicy.REPLACE,
        workRequest
    )
}

private suspend fun checkUpdates(user: String, repo: String, onUpdateFound: (UpdateInfo) -> Unit) {
    try {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://raw.githubusercontent.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        val api = retrofit.create(UpdateApi::class.java)
        // URL de GitHub para el archivo raw de control de versiones
        val updateUrl = "https://raw.githubusercontent.com/$user/$repo/main/update.json"
        val response = api.checkUpdate(updateUrl)
        
        if (response.isSuccessful) {
            val info = response.body()
            if (info != null && info.versionCode > BuildConfig.VERSION_CODE) {
                onUpdateFound(info)
            }
        }
    } catch (e: Exception) {
        Log.e("UpdateCheck", "Error checking updates", e)
    }
}

private fun downloadAndInstallApk(context: Context, url: String) {
    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle("Actualizando Finzen Listener")
        .setDescription("Descargando nueva versión...")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "finzen-update.apk")
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)

    val downloadId = downloadManager.enqueue(request)

    val onComplete = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId) {
                installApk(ctx)
                ctx.unregisterReceiver(this)
            }
        }
    }
    context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
}

private fun installApk(context: Context) {
    val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "finzen-update.apk")
    if (file.exists()) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
