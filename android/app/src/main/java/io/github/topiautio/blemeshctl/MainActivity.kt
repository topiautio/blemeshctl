package io.github.topiautio.blemeshctl

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import io.github.topiautio.blemeshctl.ble.BleMeshClient
import io.github.topiautio.blemeshctl.ble.BleMeshException
import io.github.topiautio.blemeshctl.ble.BleMeshScanner
import io.github.topiautio.blemeshctl.ble.CommandOutcomeUnknownException
import io.github.topiautio.blemeshctl.ble.DiscoveredLight
import io.github.topiautio.blemeshctl.ble.InitialConnectionException
import io.github.topiautio.blemeshctl.ble.MeshCredentials
import io.github.topiautio.blemeshctl.color.ColorWheelView
import io.github.topiautio.blemeshctl.color.WheelRgb
import io.github.topiautio.blemeshctl.protocol.AdvertisementInfo
import io.github.topiautio.blemeshctl.protocol.TelinkProtocol
import io.github.topiautio.blemeshctl.protocol.TelinkProtocolException
import io.github.topiautio.blemeshctl.script.BleMeshScript
import io.github.topiautio.blemeshctl.script.ScriptException
import io.github.topiautio.blemeshctl.script.ScriptStep
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    /** A light in the picker; [info] is null for a remembered address without cached details. */
    private data class LightChoice(val address: String, val info: AdvertisementInfo?)

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BleMeshScanner? = null
    private var client: BleMeshClient? = null
    private var operationJob: Job? = null
    private var scriptJob: Job? = null
    private var warmConnectJob: Job? = null
    private var busy = false
    private var warmConnecting = false
    private var lightChoices: List<LightChoice> = emptyList()
    private var preferredAddress: String? = null
    private var ignoreSelectionCallback = false

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var permissionButton: Button
    private lateinit var scanButton: Button
    private lateinit var deviceSpinner: Spinner
    private lateinit var deviceDetailsText: TextView
    private lateinit var meshNameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var onButton: Button
    private lateinit var offButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var colorWheel: ColorWheelView
    private lateinit var colorPreview: View
    private lateinit var colorSelectionText: TextView
    private lateinit var brightnessText: TextView
    private lateinit var brightnessSeekBar: SeekBar
    private lateinit var colorButton: Button
    private lateinit var scriptEditor: EditText
    private lateinit var loadCycleButton: Button
    private lateinit var loadRainbowButton: Button
    private lateinit var openScriptButton: Button
    private lateinit var runScriptButton: Button
    private lateinit var stopScriptButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        restoreInputs()
        configureBluetooth()
        configureListeners()
        updatePermissionButton()
    }

    override fun onStart() {
        super.onStart()
        startRememberedOrDiscover()
    }

    override fun onPause() {
        super.onPause()
        saveInputs()
    }

    override fun onDestroy() {
        operationJob?.cancel()
        scriptJob?.cancel()
        warmConnectJob?.cancel()
        uiScope.cancel()
        client?.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        permissionButton = findViewById(R.id.permissionButton)
        scanButton = findViewById(R.id.scanButton)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        deviceDetailsText = findViewById(R.id.deviceDetailsText)
        meshNameInput = findViewById(R.id.meshNameInput)
        passwordInput = findViewById(R.id.passwordInput)
        onButton = findViewById(R.id.onButton)
        offButton = findViewById(R.id.offButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        colorWheel = findViewById(R.id.colorWheel)
        colorPreview = findViewById(R.id.colorPreview)
        colorSelectionText = findViewById(R.id.colorSelectionText)
        brightnessText = findViewById(R.id.brightnessText)
        brightnessSeekBar = findViewById(R.id.brightnessSeekBar)
        colorButton = findViewById(R.id.colorButton)
        scriptEditor = findViewById(R.id.scriptEditor)
        loadCycleButton = findViewById(R.id.loadCycleButton)
        loadRainbowButton = findViewById(R.id.loadRainbowButton)
        openScriptButton = findViewById(R.id.openScriptButton)
        runScriptButton = findViewById(R.id.runScriptButton)
        stopScriptButton = findViewById(R.id.stopScriptButton)
    }

    private fun configureBluetooth() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        bluetoothAdapter = adapter
        if (adapter == null) {
            showStatus("Tämä Android-laite ei tarjoa Bluetooth-sovitinta.")
            setControlsEnabled(false)
        } else {
            scanner = BleMeshScanner(adapter)
            client = BleMeshClient(this, adapter)
        }
    }

    private fun configureListeners() {
        permissionButton.setOnClickListener { requestBluetoothPermissions() }
        scanButton.setOnClickListener { startScan() }
        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val choice = lightChoices.getOrNull(position)
                deviceDetailsText.text = choice?.let { details(it) } ?: getString(R.string.no_selected_device)
                if (choice == null) return

                val selectedByUser = !ignoreSelectionCallback
                val selectionChanged = preferredAddress != choice.address
                preferredAddress = choice.address
                rememberLight(choice)
                if (selectedByUser && selectionChanged) {
                    lightChoices = listOf(choice) + lightChoices.filter { it.address != choice.address }
                    refreshLightSpinner()
                }
                if (selectedByUser) {
                    startWarmConnection()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                deviceDetailsText.text = getString(R.string.no_selected_device)
            }
        }
        brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                brightnessText.text = getString(R.string.brightness, progress + 1)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        colorWheel.setOnColorChangedListener(::updateColorPreview)
        onButton.setOnClickListener {
            runNormalCommand(TelinkProtocol.COMMAND_ON_OFF, TelinkProtocol.onParameters(), "päälle")
        }
        offButton.setOnClickListener {
            runNormalCommand(TelinkProtocol.COMMAND_ON_OFF, TelinkProtocol.offParameters(), "pois")
        }
        colorButton.setOnClickListener { runColorCommand() }
        disconnectButton.setOnClickListener { disconnect() }
        loadCycleButton.setOnClickListener { loadAsset("rgb-cycle.blemesh") }
        loadRainbowButton.setOnClickListener { loadAsset("rainbow.blemesh") }
        openScriptButton.setOnClickListener { openScriptFile() }
        runScriptButton.setOnClickListener { runScript() }
        stopScriptButton.setOnClickListener { stopScript() }
    }

    private fun startScan() {
        val currentScanner = scanner ?: return
        if (!ensureBluetoothReady()) return
        if (operationJob?.isActive == true || scriptJob?.isActive == true) return

        operationJob = uiScope.launch {
            try {
                setBusy(true)
                showStatus("Skannataan yhteensopivia Telink-valoja 8 sekuntia…")
                val found = currentScanner.scan()
                installDiscoveredLights(found)
                if (found.isEmpty()) {
                    val remembered = preferredAddress
                    if (remembered == null) {
                        deviceDetailsText.text = getString(R.string.no_selected_device)
                        showStatus("Skannaus valmistui: yhteensopivia Telink-valoja ei löytynyt.")
                    } else {
                        showStatus("Skannaus valmistui: valo $remembered ei mainostanut juuri nyt. Se säilyy pikavalintana.")
                    }
                } else {
                    showStatus("Skannaus valmistui: löytyi ${found.size} yhteensopivaa valoa.")
                }
            } catch (exception: Exception) {
                showError(exception)
            } finally {
                operationJob = null
                setBusy(false)
                startWarmConnection()
            }
        }
    }

    private fun runColorCommand() {
        val rgb = colorWheel.selectedRgb
        val brightness = brightnessSeekBar.progress + 1
        runNormalCommand(
            TelinkProtocol.COMMAND_RGB,
            TelinkProtocol.rgbParameters(rgb.red, rgb.green, rgb.blue, brightness),
            "väri #${hex(rgb.red)}${hex(rgb.green)}${hex(rgb.blue)}, kirkkaus $brightness %",
        )
    }

    private fun runNormalCommand(opcode: Int, parameters: ByteArray, description: String) {
        val target = selectedLight() ?: return
        val currentClient = client ?: return
        if (!ensureBluetoothReady()) return
        if (operationJob?.isActive == true || scriptJob?.isActive == true) return

        val credentials = currentCredentials()
        cancelWarmConnection()
        operationJob = uiScope.launch {
            try {
                setBusy(true)
                showStatus("Avataan tarvittaessa yhteys ja lähetetään komento: $description…")
                val receipt = currentClient.send(target.address, target.info, credentials, opcode, parameters)
                val connection = connectionKind(receipt.reusedConnection, receipt.connectedDirectlyFromCache)
                updateConnectedLight(receipt.info)
                deviceDetailsText.text = receipt.info.details()
                showStatus(
                    "Komento '$description' kirjoitettiin osoitteelle ${receipt.info.address} ($connection). " +
                        "Fyysistä lopputilaa ei voida vahvistaa Androidin GATT-kuittauksesta.",
                )
            } catch (exception: Exception) {
                showError(exception)
            } finally {
                operationJob = null
                setBusy(false)
            }
        }
    }

    private fun disconnect() {
        val currentClient = client ?: return
        if (operationJob?.isActive == true || scriptJob?.isActive == true) return

        cancelWarmConnection()
        operationJob = uiScope.launch {
            try {
                setBusy(true)
                currentClient.disconnect()
                showStatus("BLE-yhteys katkaistiin. Valon tilaa ei muutettu.")
            } finally {
                operationJob = null
                setBusy(false)
            }
        }
    }

    private fun runScript() {
        val program: List<ScriptStep>
        try {
            // Validate the whole script before any Bluetooth activity.
            program = BleMeshScript.parse(scriptEditor.text.toString(), "editor.blemesh")
        } catch (exception: ScriptException) {
            showError(exception)
            return
        }
        val target = selectedLight() ?: return
        val currentClient = client ?: return
        if (!ensureBluetoothReady()) return
        if (operationJob?.isActive == true || scriptJob?.isActive == true) return

        val credentials = currentCredentials()
        cancelWarmConnection()
        scriptJob = uiScope.launch {
            try {
                setBusy(true, scriptRunning = true)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                showStatus("Skripti käynnissä. Ensimmäinen yhteys avataan tarvittaessa…")
                BleMeshScript.execute(
                    program,
                    sendCommand = { command ->
                        while (true) {
                            try {
                                val receipt = currentClient.send(
                                    target.address,
                                    target.info,
                                    credentials,
                                    command.opcode,
                                    command.parameters,
                                )
                                if (command.report) {
                                    showStatus(
                                        "Rivi ${command.line}: ${command.description} kirjoitettiin osoitteelle " +
                                            "${receipt.info.address}. Fyysistä tilaa ei vahvistettu.",
                                    )
                                }
                                break
                            } catch (exception: InitialConnectionException) {
                                // Nothing was written yet, so waiting for the light is safe.
                                showStatus(
                                    "Odotetaan valoa ${target.address}: ${exception.message}. " +
                                        "Pysäytä-painike lopettaa odotuksen.",
                                )
                                delay(1_000)
                            }
                        }
                    },
                    wait = { step ->
                        if (step.report) {
                            showStatus("Rivi ${step.line}: odotetaan ${step.milliseconds} ms…")
                        }
                        delay(step.milliseconds)
                    },
                )
                showStatus("Skripti valmistui.")
            } catch (exception: CancellationException) {
                showStatus("Skripti pysäytettiin ja BLE-yhteys katkaistaan.")
            } catch (exception: Exception) {
                showError(exception)
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                scriptJob = null
                setBusy(false)
            }
        }
    }

    private fun stopScript() {
        val running = scriptJob ?: return
        running.cancel(CancellationException("stopped by user"))
        uiScope.launch {
            runCatching { client?.disconnect() }
        }
    }

    private fun selectedLight(): LightChoice? {
        val selected = lightChoices.getOrNull(deviceSpinner.selectedItemPosition)
        if (selected == null) {
            showStatus("Skannaa ja valitse ensin yhteensopiva BleMesh-valo.")
        }
        return selected
    }

    private fun currentCredentials(): MeshCredentials =
        MeshCredentials(meshNameInput.text.toString(), passwordInput.text.toString())

    @SuppressLint("MissingPermission")
    private fun ensureBluetoothReady(): Boolean {
        if (!hasBluetoothPermissions()) {
            showStatus("Androidin Bluetooth-lupa puuttuu. Hyväksy järjestelmän Nearby devices -pyyntö.")
            requestBluetoothPermissions()
            return false
        }
        val adapter = bluetoothAdapter ?: return false
        if (!isAdapterEnabled(adapter)) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH)
                showStatus("Kytke Bluetooth päälle Androidin järjestelmäikkunasta ja yritä sitten uudelleen.")
            } catch (exception: Exception) {
                showError(exception)
            }
            return false
        }
        return true
    }

    private fun isAdapterEnabled(adapter: BluetoothAdapter): Boolean = try {
        adapter.isEnabled
    } catch (exception: SecurityException) {
        false
    }

    private fun hasBluetoothPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(permissions, REQUEST_BLUETOOTH_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH_PERMISSIONS) return
        updatePermissionButton()
        if (hasBluetoothPermissions()) {
            showStatus("Bluetooth-lupa myönnettiin. Valo etsitään tai yhdistetään automaattisesti…")
            startRememberedOrDiscover()
        } else {
            showStatus("Bluetooth-lupaa ei myönnetty. Sovellus ei voi skannata eikä ohjata valoa.")
        }
    }

    @Deprecated("Deprecated by Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH && resultCode == RESULT_OK) {
            showStatus("Bluetooth on päällä. Valo etsitään tai yhdistetään automaattisesti…")
            startRememberedOrDiscover()
        } else if (requestCode == REQUEST_OPEN_SCRIPT && resultCode == RESULT_OK) {
            readScriptFile(data)
        }
    }

    private fun updatePermissionButton() {
        permissionButton.setText(
            if (hasBluetoothPermissions()) R.string.bluetooth_permission_ok else R.string.bluetooth_permission,
        )
    }

    private fun loadAsset(name: String) {
        try {
            val text = assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
            scriptEditor.setText(text)
            showStatus("Ladattiin esimerkki $name editoriin. Sitä ei ole vielä ajettu.")
        } catch (exception: Exception) {
            showError(exception)
        }
    }

    private fun openScriptFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_OPEN_SCRIPT)
    }

    private fun readScriptFile(data: Intent?) {
        val uri = data?.data ?: return
        try {
            val text = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: throw IllegalArgumentException("Tiedostoa ei voitu lukea")
            if (text.length > MAX_SCRIPT_CHARACTERS) {
                throw IllegalArgumentException("Skriptitiedosto on liian suuri")
            }
            scriptEditor.setText(text)
            showStatus("Skripti avattiin editoriin. Se validoidaan vasta Aja skripti -painikkeesta.")
        } catch (exception: Exception) {
            showError(exception)
        }
    }

    private fun setBusy(busy: Boolean, scriptRunning: Boolean = false) {
        this.busy = busy
        updateProgressVisibility()
        val enabled = !busy
        permissionButton.isEnabled = enabled
        scanButton.isEnabled = enabled
        deviceSpinner.isEnabled = enabled
        meshNameInput.isEnabled = enabled
        passwordInput.isEnabled = enabled
        onButton.isEnabled = enabled
        offButton.isEnabled = enabled
        disconnectButton.isEnabled = enabled
        colorWheel.isEnabled = enabled
        brightnessSeekBar.isEnabled = enabled
        colorButton.isEnabled = enabled
        scriptEditor.isEnabled = enabled
        loadCycleButton.isEnabled = enabled
        loadRainbowButton.isEnabled = enabled
        openScriptButton.isEnabled = enabled
        runScriptButton.isEnabled = enabled
        stopScriptButton.isEnabled = busy && scriptRunning
    }

    private fun updateProgressVisibility() {
        progressBar.visibility = if (busy || warmConnecting) View.VISIBLE else View.GONE
    }

    private fun setControlsEnabled(enabled: Boolean) {
        permissionButton.isEnabled = enabled
        scanButton.isEnabled = enabled
        onButton.isEnabled = enabled
        offButton.isEnabled = enabled
        disconnectButton.isEnabled = enabled
        colorButton.isEnabled = enabled
        runScriptButton.isEnabled = enabled
    }

    private fun showStatus(message: String) {
        statusText.text = message
    }

    private fun showError(exception: Throwable) {
        val prefix = when (exception) {
            is CommandOutcomeUnknownException -> "Komennon lopputulos jäi epävarmaksi"
            is TelinkProtocolException -> "Telink-protokollavirhe"
            is ScriptException -> "Skriptivirhe"
            is BleMeshException -> "Bluetooth-virhe"
            else -> "Virhe"
        }
        showStatus("$prefix: ${exception.message ?: exception.javaClass.simpleName}")
    }

    private fun installDiscoveredLights(found: List<DiscoveredLight>) {
        val discovered = found.map { LightChoice(it.info.address, it.info) }
        val preferred = preferredAddress
        // Keep a remembered light selectable even when it did not advertise this time.
        val previousPreferred = lightChoices.firstOrNull { it.address == preferred }
        val preferredChoice = discovered.firstOrNull { it.address == preferred } ?: previousPreferred
        lightChoices = buildList {
            if (preferredChoice != null) add(preferredChoice)
            addAll(discovered.filter { it.address != preferred }.sortedBy { it.address })
        }.distinctBy { it.address }
        if (preferredAddress == null && lightChoices.isNotEmpty()) {
            preferredAddress = lightChoices.first().address
        }
        refreshLightSpinner()
    }

    private fun refreshLightSpinner() {
        val labels = if (lightChoices.isEmpty()) listOf("Ei yhteensopivia valoja") else lightChoices.map { label(it) }
        val selectedIndex = lightChoices.indexOfFirst { it.address == preferredAddress }.coerceAtLeast(0)

        // Programmatic selection must not look like the user picking a light.
        ignoreSelectionCallback = true
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        if (lightChoices.isNotEmpty()) {
            deviceSpinner.setSelection(selectedIndex)
            deviceDetailsText.text = details(lightChoices[selectedIndex])
        } else {
            deviceDetailsText.text = getString(R.string.no_selected_device)
        }
        deviceSpinner.post { ignoreSelectionCallback = false }
    }

    private fun label(choice: LightChoice): String {
        val base = choice.info?.displayLabel() ?: "BleMesh  ${choice.address}"
        return if (choice.address == preferredAddress) "Viimeksi käytetty · $base" else base
    }

    private fun details(choice: LightChoice): String =
        choice.info?.details()
            ?: "Viimeksi käytetty osoite ${choice.address} · tiedot päivittyvät, kun yhteys avautuu."

    private fun rememberLight(choice: LightChoice) {
        val editor = getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(PREF_ADDRESS, choice.address)
        val info = choice.info
        if (info != null) {
            editor
                .putInt(PREF_LIGHT_MESH_UUID, info.meshUuid)
                .putLong(PREF_LIGHT_COMMAND_MAC_PREFIX, info.commandMacPrefix)
                .putInt(PREF_LIGHT_PRODUCT_UUID, info.productUuid)
                .putInt(PREF_LIGHT_STATUS, info.status)
                .putInt(PREF_LIGHT_MESH_ADDRESS, info.meshAddress)
            if (info.name == null) {
                editor.remove(PREF_LIGHT_NAME)
            } else {
                editor.putString(PREF_LIGHT_NAME, info.name)
            }
        }
        editor.apply()
    }

    private fun updateConnectedLight(info: AdvertisementInfo) {
        preferredAddress = info.address
        val choice = LightChoice(info.address, info)
        rememberLight(choice)
        val current = lightChoices.firstOrNull { it.address == info.address }
        if (current?.info == info && lightChoices.firstOrNull()?.address == info.address) return

        lightChoices = buildList {
            add(choice)
            addAll(lightChoices.filter { it.address != info.address })
        }
        refreshLightSpinner()
    }

    private fun startRememberedOrDiscover() {
        if (lightChoices.isNotEmpty()) {
            startWarmConnection()
            return
        }
        if (operationJob?.isActive == true || scriptJob?.isActive == true) return
        if (!hasBluetoothPermissions()) return
        val adapter = bluetoothAdapter ?: return
        if (isAdapterEnabled(adapter)) {
            startScan()
        }
    }

    /** Connect to the selected light ahead of time so the first command is quick. */
    private fun startWarmConnection() {
        val target = lightChoices.getOrNull(deviceSpinner.selectedItemPosition)
            ?: lightChoices.firstOrNull { it.address == preferredAddress }
            ?: return
        val currentClient = client ?: return
        if (warmConnectJob?.isActive == true || operationJob?.isActive == true || scriptJob?.isActive == true) return
        if (!hasBluetoothPermissions()) return
        val adapter = bluetoothAdapter ?: return
        if (!isAdapterEnabled(adapter)) return

        val credentials = currentCredentials()
        warmConnecting = true
        updateProgressVisibility()
        warmConnectJob = uiScope.launch {
            try {
                showStatus("Yhdistetään valoon ${target.address} valmiiksi… Ohjaimet ovat jo käytettävissä.")
                val receipt = currentClient.connect(target.address, target.info, credentials)
                updateConnectedLight(receipt.info)
                val connection = connectionKind(receipt.reusedConnection, receipt.connectedDirectlyFromCache)
                showStatus("Valo ${receipt.info.address} on valmiina ($connection). Seuraava komento lähtee heti.")
            } catch (exception: CancellationException) {
                // A command or script replaced the warm connection on purpose.
            } catch (exception: Exception) {
                showError(exception)
            } finally {
                warmConnecting = false
                warmConnectJob = null
                updateProgressVisibility()
            }
        }
    }

    private fun cancelWarmConnection() {
        warmConnectJob?.cancel()
        warmConnectJob = null
        warmConnecting = false
        updateProgressVisibility()
    }

    private fun connectionKind(reusedConnection: Boolean, connectedDirectlyFromCache: Boolean): String = when {
        reusedConnection -> "avoin yhteys"
        connectedDirectlyFromCache -> "suora muistiyhteys"
        else -> "uusi skannattu yhteys"
    }

    private fun updateColorPreview(rgb: WheelRgb) {
        colorPreview.backgroundTintList = ColorStateList.valueOf(Color.rgb(rgb.red, rgb.green, rgb.blue))
        colorPreview.contentDescription =
            "Valitun värin esikatselu: punainen ${rgb.red}, vihreä ${rgb.green}, sininen ${rgb.blue}."
        colorSelectionText.setText(R.string.selected_colour)
    }

    private fun restoreInputs() {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        meshNameInput.setText(preferences.getString(PREF_MESH_NAME, TelinkProtocol.DEFAULT_MESH_NAME))
        passwordInput.setText(preferences.getString(PREF_PASSWORD, TelinkProtocol.DEFAULT_PASSWORD))

        val storedRgb = runCatching {
            TelinkProtocol.parseRgb(preferences.getString(PREF_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR)
        }.getOrElse { TelinkProtocol.parseRgb(DEFAULT_COLOR) }
        colorWheel.setRgb(storedRgb.red, storedRgb.green, storedRgb.blue)
        updateColorPreview(colorWheel.selectedRgb)
        brightnessSeekBar.progress = preferences.getInt(PREF_BRIGHTNESS, 100).coerceIn(1, 100) - 1
        brightnessText.text = getString(R.string.brightness, brightnessSeekBar.progress + 1)

        preferredAddress = preferences.getString(PREF_ADDRESS, null)?.uppercase(Locale.ROOT)
        val address = preferredAddress
        if (address != null) {
            val info = if (
                preferences.contains(PREF_LIGHT_MESH_ADDRESS) && preferences.contains(PREF_LIGHT_COMMAND_MAC_PREFIX)
            ) {
                AdvertisementInfo(
                    address = address,
                    name = preferences.getString(PREF_LIGHT_NAME, null),
                    meshUuid = preferences.getInt(PREF_LIGHT_MESH_UUID, 0),
                    commandMacPrefix = preferences.getLong(PREF_LIGHT_COMMAND_MAC_PREFIX, 0),
                    productUuid = preferences.getInt(PREF_LIGHT_PRODUCT_UUID, 0),
                    status = preferences.getInt(PREF_LIGHT_STATUS, 0),
                    meshAddress = preferences.getInt(PREF_LIGHT_MESH_ADDRESS, 0),
                    rssi = null,
                )
            } else {
                null
            }
            lightChoices = listOf(LightChoice(address, info))
            refreshLightSpinner()
            showStatus("Viimeksi käytetty valo $address löytyi muistista. Yhteys avataan automaattisesti.")
        } else {
            refreshLightSpinner()
            showStatus("Valmis. Anna Bluetooth-lupa ja skannaa valo ensimmäisellä käyttökerralla.")
        }

        preferences.getString(PREF_SCRIPT, null)?.let { scriptEditor.setText(it) }
    }

    private fun saveInputs() {
        val rgb = colorWheel.selectedRgb
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putString(PREF_MESH_NAME, meshNameInput.text.toString())
            .putString(PREF_PASSWORD, passwordInput.text.toString())
            .putString(PREF_COLOR, hex(rgb.red) + hex(rgb.green) + hex(rgb.blue))
            .putInt(PREF_BRIGHTNESS, brightnessSeekBar.progress + 1)
            .putString(PREF_ADDRESS, preferredAddress)
            .putString(PREF_SCRIPT, scriptEditor.text.toString().take(MAX_SCRIPT_CHARACTERS))
            .apply()
    }

    private fun hex(value: Int): String = value.toString(16).padStart(2, '0').lowercase(Locale.ROOT)

    private companion object {
        const val MAX_SCRIPT_CHARACTERS = 262_144
        const val DEFAULT_COLOR = "00ff00"
        const val PREFERENCES = "blemeshctl"
        const val PREF_ADDRESS = "address"
        const val PREF_BRIGHTNESS = "brightness"
        const val PREF_COLOR = "color"
        const val PREF_LIGHT_COMMAND_MAC_PREFIX = "light_command_mac_prefix"
        const val PREF_LIGHT_MESH_ADDRESS = "light_mesh_address"
        const val PREF_LIGHT_MESH_UUID = "light_mesh_uuid"
        const val PREF_LIGHT_NAME = "light_name"
        const val PREF_LIGHT_PRODUCT_UUID = "light_product_uuid"
        const val PREF_LIGHT_STATUS = "light_status"
        const val PREF_MESH_NAME = "mesh_name"
        const val PREF_PASSWORD = "password"
        const val PREF_SCRIPT = "script"
        const val REQUEST_BLUETOOTH_PERMISSIONS = 100
        const val REQUEST_ENABLE_BLUETOOTH = 101
        const val REQUEST_OPEN_SCRIPT = 102
    }
}
