package com.example.safetyclick

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safetyclick.ui.theme.SafetyClickTheme

// ── State Management ─────────────────────────────────────────────────────────

sealed class Screen {
    object RoleSelection : Screen()
    object UserDashboard : Screen()
    object DeviceScanner : Screen()
    object GuardianSetup : Screen()
    object GuardianDashboard : Screen() // Placeholder for future Guardian Home
}

class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("SentinelSession", Context.MODE_PRIVATE)
    
    fun getRole(): String {
        return prefs.getString("ROLE", "NONE") ?: "NONE"
    }
    
    fun setRole(role: String) {
        prefs.edit().putString("ROLE", role).apply()
    }
}

// ── Main Activity ────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private var bleManager: BleManager? = null
    lateinit var sessionManager: SessionManager

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Only start BLE scan if they are in USER mode
        if (sessionManager.getRole() == "USER") {
            bleManager = BleManager(this)
            bleManager?.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        launcher.launch(permissions.toTypedArray())

        val scanner = SafetyScanner(this)

        setContent {
            SafetyClickTheme {
                AppRoot(scanner = scanner, activity = this, sessionManager = sessionManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager?.disconnect()
    }
}

// ── App Root Router ──────────────────────────────────────────────────────────

@Composable
fun AppRoot(scanner: SafetyScanner, activity: ComponentActivity, sessionManager: SessionManager) {
    // Determine initial screen based on saved role
    val initialScreen = when (sessionManager.getRole()) {
        "USER" -> Screen.UserDashboard
        "GUARDIAN" -> Screen.GuardianDashboard
        else -> Screen.RoleSelection
    }
    
    var currentScreen by remember { mutableStateOf<Screen>(initialScreen) }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
        },
        label = "screenTransition"
    ) { screen ->
        when (screen) {
            is Screen.RoleSelection -> RoleSelectionScreen(
                onRolePicked = { role ->
                    sessionManager.setRole(role)
                    currentScreen = if (role == "USER") Screen.UserDashboard else Screen.GuardianDashboard
                }
            )
            is Screen.UserDashboard -> SentinelScreen(
                scanner = scanner,
                activity = activity,
                onNavigate = { currentScreen = it },
                onResetRole = {
                    sessionManager.setRole("NONE")
                    currentScreen = Screen.RoleSelection
                }
            )
            is Screen.DeviceScanner -> DeviceScanScreen(
                activity = activity,
                onBack = { currentScreen = Screen.UserDashboard }
            )
            is Screen.GuardianSetup -> GuardianSetupScreen(
                scanner = scanner,
                onBack = { currentScreen = Screen.UserDashboard }
            )
            is Screen.GuardianDashboard -> GuardianDashboardScreen(
                onResetRole = {
                    sessionManager.setRole("NONE")
                    currentScreen = Screen.RoleSelection
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Role Selection Screen (Onboarding)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RoleSelectionScreen(onRolePicked: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 100.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SENTINEL", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
            Spacer(Modifier.height(8.dp))
            Text("Personal Safety System", color = Color(0xFF8E8E93), fontSize = 14.sp, letterSpacing = 0.5.sp)
            
            Spacer(Modifier.height(80.dp))
            Text("How will you use this app?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(32.dp))

            RoleCard(
                title = "I need protection",
                subtitle = "I have the hardware device and want to secure my safety.",
                icon = Icons.Default.Lock,
                color = Color(0xFF0A84FF),
                onClick = { onRolePicked("USER") }
            )
            
            Spacer(Modifier.height(20.dp))
            
            RoleCard(
                title = "I am a Guardian",
                subtitle = "I am monitoring someone else and need to receive their alerts.",
                icon = Icons.Default.Person,
                color = Color(0xFF30D158),
                onClick = { onRolePicked("GUARDIAN") }
            )
        }
    }
}

@Composable
fun RoleCard(title: String, subtitle: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1E), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(56.dp).background(color.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color(0xFF8E8E93), fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. User Dashboard (SentinelScreen)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SentinelScreen(
    scanner: SafetyScanner,
    activity: ComponentActivity,
    onNavigate: (Screen) -> Unit,
    onResetRole: () -> Unit
) {
    var isArmed by remember { mutableStateOf(false) }
    val contactSaved = scanner.getEmergencyContactNumber() != null

    val accentColor by animateColorAsState(
        targetValue = if (isArmed) Color(0xFFFF453A) else Color(0xFF0A84FF),
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "accent"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alpha"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 64.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppHeader(isArmed = isArmed)
            Spacer(Modifier.height(40.dp))

            ArmButton(
                isArmed = isArmed,
                accentColor = accentColor,
                pulseScale = if (isArmed) pulseScale else 1f,
                pulseAlpha = if (isArmed) pulseAlpha else 0.4f,
                onToggle = {
                    val intent = Intent(activity, SecurityService::class.java)
                    if (isArmed) activity.stopService(intent)
                    else activity.startForegroundService(intent)
                    isArmed = !isArmed
                }
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Volume Up · Hold 1.5 seconds to trigger",
                color = Color(0xFF48484A),
                fontSize = 11.sp,
                letterSpacing = 0.3.sp
            )
            Spacer(Modifier.height(40.dp))

            // Navigation to Guardian Setup
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(20.dp))
                    .clickable { onNavigate(Screen.GuardianSetup) }
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Guardians", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (contactSaved) "1 Contact Secured" else "Setup required", 
                        color = if (contactSaved) Color(0xFF30D158) else Color(0xFFFF453A), 
                        fontSize = 13.sp
                    )
                }
                Text("Edit ›", color = Color(0xFF8E8E93), fontSize = 15.sp)
            }

            Spacer(Modifier.height(16.dp))
            SystemStatusCard(scanner = scanner, onOpenScanner = { onNavigate(Screen.DeviceScanner) })

            Spacer(Modifier.height(32.dp))
            TextButton(onClick = onResetRole) {
                Text("Switch to Guardian Mode", color = Color(0xFF0A84FF), fontSize = 14.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Guardian Setup Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GuardianSetupScreen(scanner: SafetyScanner, onBack: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    var contactName by remember { mutableStateOf(scanner.getEmergencyContactName() ?: "") }
    var contactNumber by remember { mutableStateOf(scanner.getEmergencyContactNumber() ?: "") }
    var isContactSaved by remember { mutableStateOf(scanner.getEmergencyContactNumber() != null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 60.dp, bottom = 32.dp)
        ) {
            // Top Bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("← Back", color = Color(0xFF0A84FF), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Guardians", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("These trusted contacts will receive your SOS alerts and live location if you trigger the system.", color = Color(0xFF8E8E93), fontSize = 14.sp, lineHeight = 20.sp)
            
            Spacer(Modifier.height(32.dp))

            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1C1C1E), RoundedCornerShape(20.dp)).padding(20.dp)
            ) {
                Text("Primary Guardian", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                
                // Name Field
                TextField(
                    value = contactName,
                    onValueChange = { contactName = it; isContactSaved = false },
                    placeholder = { Text("Guardian Name", color = Color(0xFF48484A)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF2C2C2E),
                        unfocusedContainerColor = Color(0xFF2C2C2E),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF0A84FF)
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
                
                Spacer(Modifier.height(12.dp))

                // Phone Field
                TextField(
                    value = contactNumber,
                    onValueChange = { contactNumber = it; isContactSaved = false },
                    placeholder = { Text("+1 (000) 000-0000", color = Color(0xFF48484A)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    trailingIcon = {
                        if (isContactSaved) Icon(Icons.Default.Check, "Saved", tint = Color(0xFF30D158), modifier = Modifier.size(18.dp))
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF2C2C2E),
                        unfocusedContainerColor = Color(0xFF2C2C2E),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF0A84FF)
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        if (contactNumber.length >= 10 && contactName.isNotBlank()) {
                            scanner.saveEmergencyContact(contactName, contactNumber)
                            isContactSaved = true
                            focusManager.clearFocus()
                            Toast.makeText(context, "Guardian secured", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Enter a valid name and phone number", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isContactSaved) Color(0xFF30D158).copy(0.15f) else Color(0xFF0A84FF)
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Text(
                        text = if (isContactSaved) "Guardian Saved" else "Save Guardian",
                        color = if (isContactSaved) Color(0xFF30D158) else Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Guardian Dashboard (Placeholder)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GuardianDashboardScreen(onResetRole: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF30D158), modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(24.dp))
            Text("Guardian Mode", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "You are actively monitoring for SOS alerts. If your ward triggers an alert, it will appear here immediately.",
                color = Color(0xFF8E8E93),
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                lineHeight = 22.sp
            )
            Spacer(Modifier.height(48.dp))
            TextButton(onClick = onResetRole) {
                Text("Change Role", color = Color(0xFF0A84FF), fontSize = 16.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Device Scanner Screen (Unchanged functionally)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DeviceScanScreen(activity: ComponentActivity, onBack: () -> Unit) {
    val bleManager = remember { BleManager(activity) }
    val bleState by BleManager.state.collectAsState()
    val devices by BleManager.scannedDevices.collectAsState()

    LaunchedEffect(Unit) { bleManager.startDiscovery() }
    DisposableEffect(Unit) { onDispose { bleManager.stopScan() } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 60.dp, bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("← Back", color = Color(0xFF0A84FF), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Nearby Devices", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bleState == BleManager.BleState.SCANNING) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color(0xFF0A84FF), strokeWidth = 1.5.dp)
                }
                Text(
                    text = when (bleState) {
                        BleManager.BleState.SCANNING -> "Scanning for Bluetooth devices..."
                        BleManager.BleState.CONNECTING -> "Connecting..."
                        BleManager.BleState.CONNECTED -> "Connected"
                        BleManager.BleState.DISCONNECTED -> if (devices.isEmpty()) "No devices found. Try scanning again." else "${devices.size} device(s) found"
                    },
                    color = Color(0xFF8E8E93), fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(24.dp))

            if (devices.isEmpty() && bleState != BleManager.BleState.SCANNING) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    Text("Make sure your device is powered on\nand nearby", color = Color(0xFF48484A), fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(devices, key = { it.address }) { device ->
                        DeviceCard(device = device, onConnect = { bleManager.connectTo(device.device); onBack() })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { bleManager.startDiscovery() },
                enabled = bleState != BleManager.BleState.SCANNING,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E), disabledContainerColor = Color(0xFF1C1C1E))
            ) {
                Text(
                    text = if (bleState == BleManager.BleState.SCANNING) "Scanning..." else "Scan Again",
                    color = if (bleState == BleManager.BleState.SCANNING) Color(0xFF48484A) else Color(0xFF0A84FF),
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
fun DeviceCard(device: ScannedDevice, onConnect: () -> Unit) {
    val signalColor = when {
        device.rssi > -60 -> Color(0xFF30D158)
        device.rssi > -75 -> Color(0xFFFF9F0A)
        else -> Color(0xFFFF453A)
    }
    val signalLabel = when {
        device.rssi > -60 -> "Strong"
        device.rssi > -75 -> "Good"
        else -> "Weak"
    }

    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF1C1C1E), RoundedCornerShape(14.dp)).clickable(onClick = onConnect).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(device.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(device.address, color = Color(0xFF8E8E93), fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(6.dp).background(signalColor, CircleShape))
                Text(signalLabel, color = signalColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(4.dp))
            Text("Connect  →", color = Color(0xFF0A84FF), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared UI Components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AppHeader(isArmed: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SENTINEL", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
        Spacer(Modifier.height(6.dp))
        Text("Personal Safety System", color = Color(0xFF8E8E93), fontSize = 13.sp, letterSpacing = 0.3.sp)
        Spacer(Modifier.height(16.dp))

        val dotColor by animateColorAsState(targetValue = if (isArmed) Color(0xFFFF453A) else Color(0xFF636366), animationSpec = tween(500), label = "dot")
        Row(
            modifier = Modifier.background(Color(0xFF1C1C1E), RoundedCornerShape(100.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
            Text(if (isArmed) "ARMED" else "STANDBY", color = Color(0xFF8E8E93), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        }
    }
}

@Composable
fun ArmButton(isArmed: Boolean, accentColor: Color, pulseScale: Float, pulseAlpha: Float, onToggle: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(224.dp)) {
        Canvas(modifier = Modifier.size(224.dp).scale(pulseScale)) {
            drawCircle(color = accentColor.copy(alpha = pulseAlpha * 0.12f), radius = size.minDimension / 2f)
            drawCircle(color = accentColor.copy(alpha = pulseAlpha), radius = size.minDimension / 2f, style = Stroke(width = 1.6.dp.toPx()))
        }
        Box(modifier = Modifier.size(196.dp).border(0.5.dp, accentColor.copy(0.15f), CircleShape))
        Button(
            onClick = onToggle, modifier = Modifier.size(180.dp), shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E)), elevation = ButtonDefaults.buttonElevation(0.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (isArmed) "DISARM" else "ARM", color = accentColor, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                if (!isArmed) {
                    Spacer(Modifier.height(4.dp))
                    Text("TAP TO ACTIVATE", color = Color(0xFF48484A), fontSize = 9.sp, letterSpacing = 1.5.sp)
                }
            }
        }
    }
}

@Composable
fun SystemStatusCard(scanner: SafetyScanner, onOpenScanner: () -> Unit) {
    val gpsOk = scanner.isGpsEnabled()
    val permsOk = scanner.hasPermissions()
    val battOk = !scanner.isBatteryOptimized()
    val bleState by BleManager.state.collectAsState()

    val (bleLabel, bleColor) = when (bleState) {
        BleManager.BleState.CONNECTED -> "Connected" to Color(0xFF30D158)
        BleManager.BleState.CONNECTING -> "Connecting..." to Color(0xFFFF9F0A)
        BleManager.BleState.SCANNING -> "Searching..." to Color(0xFFFF9F0A)
        BleManager.BleState.DISCONNECTED -> "Tap to Scan" to Color(0xFF8E8E93)
    }

    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1C1C1E), RoundedCornerShape(20.dp)).padding(20.dp)) {
        Text("System Status", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        StatusRow("Location Services", if (gpsOk) "Enabled" else "Disabled", if (gpsOk) Color(0xFF30D158) else Color(0xFFFF453A))
        HorizontalDivider(color = Color(0xFF2C2C2E), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 14.dp))
        StatusRow("Permissions", if (permsOk) "Granted" else "Required", if (permsOk) Color(0xFF30D158) else Color(0xFFFF453A))
        HorizontalDivider(color = Color(0xFF2C2C2E), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 14.dp))
        StatusRow("Battery", if (battOk) "Unrestricted" else "Optimized", if (battOk) Color(0xFF30D158) else Color(0xFFFF453A))
        HorizontalDivider(color = Color(0xFF2C2C2E), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenScanner),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Hardware Device", color = Color(0xFF8E8E93), fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(bleLabel, color = bleColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("›", color = Color(0xFF3A3A3C), fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun StatusRow(label: String, value: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFF8E8E93), fontSize = 14.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}