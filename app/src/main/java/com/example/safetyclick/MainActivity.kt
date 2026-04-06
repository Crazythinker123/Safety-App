package com.example.safetyclick

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safetyclick.ui.theme.SafetyClickTheme

class MainActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        launcher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS
        ))

        val scanner = SafetyScanner(this)

        setContent {
            SafetyClickTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0E14)) {
                    val focusManager = LocalFocusManager.current // Used to stop the cursor blinking
                    var isArmed by remember { mutableStateOf(false) }
                    var contactNumber by remember { mutableStateOf(scanner.getEmergencyContact() ?: "") }
                    var isContactSaved by remember { mutableStateOf(scanner.getEmergencyContact() != null) }

                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("SENTINEL OS", color = Color.Cyan, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)

                        Spacer(modifier = Modifier.height(20.dp))

                        // 1. EMERGENCY CONTACT INPUT
                        TextField(
                            value = contactNumber,
                            onValueChange = {
                                contactNumber = it
                                isContactSaved = false // Hide tick if they edit
                            },
                            label = { Text("EMERGENCY CONTACT #", color = if(isContactSaved) Color.Green else Color.Gray) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                if (isContactSaved) {
                                    Icon(Icons.Default.CheckCircle, "Saved", tint = Color.Green)
                                }
                            },
                            colors = TextFieldDefaults.colors(
                                unfocusedContainerColor = Color.White.copy(0.05f),
                                focusedTextColor = Color.White,
                                focusedIndicatorColor = if(isContactSaved) Color.Green else Color.Cyan
                            )
                        )

                        // 2. SAVE & LOCK BUTTON
                        Button(
                            onClick = {
                                if (contactNumber.length >= 10) {
                                    scanner.saveEmergencyContact(contactNumber)
                                    isContactSaved = true
                                    focusManager.clearFocus() // THIS STOPS THE CURSOR BLINKING
                                    Toast.makeText(this@MainActivity, "Contact Secured 🛡️", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "Invalid Number", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isContactSaved) Color.Green.copy(0.15f) else Color.Cyan.copy(0.1f)
                            )
                        ) {
                            Text(
                                text = if (isContactSaved) "✓ CONTACT SAVED" else "CONFIRM CONTACT",
                                color = if (isContactSaved) Color.Green else Color.Cyan,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(40.dp))

                        // 3. ARM/DISARM TOGGLE
                        Button(
                            onClick = {
                                val intent = Intent(this@MainActivity, SecurityService::class.java)
                                if (isArmed) stopService(intent) else startForegroundService(intent)
                                isArmed = !isArmed
                            },
                            modifier = Modifier.size(180.dp).border(2.dp, if (isArmed) Color.Red else Color.Cyan, CircleShape),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
                        ) {
                            Text(if (isArmed) "DISARM" else "ARM", color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(30.dp))

                        DiagnosticCard("GPS", if(scanner.isGpsEnabled()) "READY" else "OFF", scanner.isGpsEnabled())
                        DiagnosticCard("PERMISSIONS", if(scanner.hasPermissions()) "SECURE" else "FIX", scanner.hasPermissions())
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticCard(label: String, status: String, isOk: Boolean) {
    val color = if (isOk) Color.Green else Color.Red
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color.White.copy(0.05f), RoundedCornerShape(8.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.LightGray, fontSize = 12.sp)
        Text(status, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}