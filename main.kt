package com.example.gadgetapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.room.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import okhttp3.*
import org.eclipse.paho.client.mqttv3.*
import java.io.IOException
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.TimeUnit

// Room Entities
@Entity
data class User(
    @PrimaryKey(autoGenerate = true) val uid: Int,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "password_hash") val passwordHash: String // Armazena o hash da senha
)

@Entity
data class Command(
    @PrimaryKey(autoGenerate = true) val id: Int,
    @ColumnInfo(name = "command") val command: String,
    @ColumnInfo(name = "status") val status: String // pending, success, failed
)

// DAOs
@Dao
interface UserDao {
    @Query("SELECT * FROM user WHERE username = :username LIMIT 1")
    fun findByUsername(username: String): User?

    @Insert
    fun insert(user: User)
}

@Dao
interface CommandDao {
    @Insert
    fun insert(command: Command)

    @Query("SELECT * FROM command WHERE status = 'pending' ORDER BY id ASC LIMIT 1")
    fun getNextPendingCommand(): Command?

    @Update
    fun updateCommandStatus(command: Command)

    @Query("SELECT * FROM command ORDER BY id DESC")
    fun getAllCommands(): Flow<List<Command>>
}

// AppDatabase
@Database(entities = [User::class, Command::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun commandDao(): CommandDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gadget-database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class MainActivity : AppCompatActivity(), MqttCallbackExtended {

    private val TAG = "MainActivity"
    private val REQUEST_CODE_PERMISSIONS = 101
    private val REQUEST_CODE_SPEECH_INPUT = 100
    private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Substitua pelo seu UUID

    private val PREFS_NAME = "GadgetAppPreferences"
    private val KEY_NAME = "userName"
    private val KEY_COMMAND_TREATMENT = "commandTreatment"
    private val KEY_RESPONSE_TREATMENT = "responseTreatment"

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private lateinit var speechRecognizer: SpeechRecognizer
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private lateinit var database: AppDatabase
    private var lastCommandTime: Long = 0
    private var feedbackScheduled = false
    private var lastInteractionType: InteractionType = InteractionType.NONE
    private var responseCounter = 0

    private lateinit var mqttClient: MqttClient

    private val requiredPermissions = arrayOf(
        Manifest.permission.SET_ALARM,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.RECORD_AUDIO // Permissão para reconhecimento de voz
    )

    private enum class InteractionType { COMMAND, RESPONSE, NONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize database
        database = AppDatabase.getDatabase(applicationContext)

        // Check if user is logged in
        if (!isUserLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java)) // Implemente LoginActivity
            finish()
            return
        }

        // Request permissions
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_CODE_PERMISSIONS)
        }

        // Bluetooth setup
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported")
            return
        }

        // Speech recognition setup
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())

        // MQTT setup
        val serverUri = "tcp://YOUR_MQTT_BROKER_ADDRESS:1883" // Substitua pelo seu endereço MQTT
        mqttClient = MqttClient(serverUri, MqttClient.generateClientId(), null)
        mqttClient.setCallback(this)

        // Click listeners
        btnVoiceInput.setOnClickListener {
            speechRecognizer.startListening(speechRecognizerIntent)
        }
        btnSend.setOnClickListener {
            val command = editTextCommand.text.toString().trim()
            if (command.isNotBlank() && isCommandAllowed()) {
                textViewResponse.append("\nYou: $command")
                sendCommand(command)
                editTextCommand.text.clear()
            } else if (!isCommandAllowed()) {
                Toast.makeText(this, "Aguarde um momento antes de enviar outro comando", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Digite um comando", Toast.LENGTH_SHORT).show()
            }
        }
        btnSaveTreatment.setOnClickListener {
            val name = editTextName.text.toString().trim()
            val commandTreatment = radioGroupCommandTreatment.checkedRadioButtonId
            val responseTreatment = radioGroupResponseTreatment.checkedRadioButtonId
            saveUserPreferences(name, commandTreatment, responseTreatment)
            Toast.makeText(this, "Preferências salvas com sucesso!", Toast.LENGTH_SHORT).show()
        }
    }

    // ... (onRequestPermissionsResult, allPermissionsGranted, isBluetoothConnected, isCommandAllowed)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SPEECH_INPUT) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spokenText = result?.get(0) ?: ""
                editTextCommand.setText(spokenText)
                btnSend.performClick()
            }
        }
    }

    private fun sendCommand(command: String) {
        val sanitizedCommand = sanitizeInput(command)
        scope.launch {
            if (isInternetAvailable()) {
                // Verificar se o comando está no cache
                val cachedResponse = cache[sanitizedCommand]
                if (cachedResponse != null) {
                    handleGadgetResponse(cachedResponse)
                } else {
                    sendCommandViaInternet(sanitizedCommand)
                }
            } else if (isBluetoothConnected()) {
                outputStream?.write(sanitizedCommand.toByteArray())
            } else {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Not connected to Gadget", Toast.LENGTH_SHORT).show()
                }
            }
        }

        lastCommandTime = System.currentTimeMillis()
    }

    private fun sendCommandViaInternet(command: String) {
        // Add your implementation here

        client.newCall(Request.Builder().url("https://your-api-url.com/send-command").post(FormBody.Builder().add("command", command).build()).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to send command: $e")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    handleGadgetResponse(responseBody ?: "Empty response")
                } else {
                    handleGadgetResponse("Failed to send command")
                }
            }
        })
    }
}