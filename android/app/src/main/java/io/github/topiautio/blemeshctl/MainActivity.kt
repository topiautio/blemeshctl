package io.github.topiautio.blemeshctl

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
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
import io.github.topiautio.blemeshctl.group.FailureKind
import io.github.topiautio.blemeshctl.group.GroupRunner
import io.github.topiautio.blemeshctl.group.MemberResult
import io.github.topiautio.blemeshctl.library.LibraryException
import io.github.topiautio.blemeshctl.library.LightGroup
import io.github.topiautio.blemeshctl.library.LightLibrary
import io.github.topiautio.blemeshctl.library.LightNames
import io.github.topiautio.blemeshctl.library.Scene
import io.github.topiautio.blemeshctl.library.SceneControls
import io.github.topiautio.blemeshctl.library.SceneText
import io.github.topiautio.blemeshctl.library.SharedPreferencesLibraryStorage
import io.github.topiautio.blemeshctl.protocol.AdvertisementInfo
import io.github.topiautio.blemeshctl.protocol.TelinkProtocol
import io.github.topiautio.blemeshctl.protocol.TelinkProtocolException
import io.github.topiautio.blemeshctl.script.BleMeshScript
import io.github.topiautio.blemeshctl.script.LightCommand
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
    private lateinit var library: LightLibrary
    /** Groups in [targetSpinner] after its first "selected light" entry. */
    private var targetGroups: List<LightGroup> = emptyList()
    private var ignoreTargetCallback = false
    /** One client per group member, so a group keeps one GATT connection per light. */
    private val groupClients = LinkedHashMap<String, BleMeshClient>()
    /** Advertisement details seen this session, so group members can connect without a scan. */
    private val lightInfo = HashMap<String, AdvertisementInfo>()

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var permissionButton: Button
    private lateinit var scanButton: Button
    private lateinit var deviceSpinner: Spinner
    private lateinit var deviceDetailsText: TextView
    private lateinit var renameLightButton: Button
    private lateinit var targetSpinner: Spinner
    private lateinit var newGroupButton: Button
    private lateinit var editGroupButton: Button
    private lateinit var deleteGroupButton: Button
    private lateinit var sceneList: LinearLayout
    private lateinit var noScenesText: TextView
    private lateinit var saveSceneButton: Button
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
    private lateinit var whiteButton: Button
    private lateinit var scriptEditor: EditText
    private lateinit var loadCycleButton: Button
    private lateinit var loadRainbowButton: Button
    private lateinit var openScriptButton: Button
    private lateinit var runScriptButton: Button
    private lateinit var stopScriptButton: Button
    private lateinit var pureBlackCheckBox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        // The window background and system bar colours are read when the decor is
        // installed, so the theme has to be chosen before the activity is created.
        if (isNightMode() && getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(PREF_PURE_BLACK, false)) {
            setTheme(R.style.Theme_BleMeshCtl_PureBlack)
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        library = LightLibrary(SharedPreferencesLibraryStorage(this))
        restoreInputs()
        refreshTargetSpinner(getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(PREF_TARGET_GROUP, null))
        configureBluetooth()
        // Scene buttons are enabled only when a Bluetooth client exists.
        refreshSceneList()
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
        releaseGroupClients()
        super.onDestroy()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        permissionButton = findViewById(R.id.permissionButton)
        scanButton = findViewById(R.id.scanButton)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        deviceDetailsText = findViewById(R.id.deviceDetailsText)
        renameLightButton = findViewById(R.id.renameLightButton)
        targetSpinner = findViewById(R.id.targetSpinner)
        newGroupButton = findViewById(R.id.newGroupButton)
        editGroupButton = findViewById(R.id.editGroupButton)
        deleteGroupButton = findViewById(R.id.deleteGroupButton)
        sceneList = findViewById(R.id.sceneList)
        noScenesText = findViewById(R.id.noScenesText)
        saveSceneButton = findViewById(R.id.saveSceneButton)
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
        whiteButton = findViewById(R.id.whiteButton)
        scriptEditor = findViewById(R.id.scriptEditor)
        loadCycleButton = findViewById(R.id.loadCycleButton)
        loadRainbowButton = findViewById(R.id.loadRainbowButton)
        openScriptButton = findViewById(R.id.openScriptButton)
        runScriptButton = findViewById(R.id.runScriptButton)
        stopScriptButton = findViewById(R.id.stopScriptButton)
        pureBlackCheckBox = findViewById(R.id.pureBlackCheckBox)
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
        targetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateGroupButtons()
                if (ignoreTargetCallback) return
                if (selectedGroup() == null) {
                    // Back to one light: its connection must not compete with group connections.
                    releaseGroupClients()
                    startWarmConnection()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        renameLightButton.setOnClickListener { showRenameLightDialog() }
        newGroupButton.setOnClickListener { showGroupDialog(null) }
        editGroupButton.setOnClickListener { selectedGroup()?.let { showGroupDialog(it) } }
        deleteGroupButton.setOnClickListener { selectedGroup()?.let { confirmDeleteGroup(it) } }
        saveSceneButton.setOnClickListener { showSaveSceneDialog() }
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
        whiteButton.setOnClickListener { runWhiteCommand() }
        disconnectButton.setOnClickListener { disconnect() }
        loadCycleButton.setOnClickListener { loadAsset("rgb-cycle.blemesh") }
        loadRainbowButton.setOnClickListener { loadAsset("rainbow.blemesh") }
        openScriptButton.setOnClickListener { openScriptFile() }
        runScriptButton.setOnClickListener { runScript() }
        stopScriptButton.setOnClickListener { stopScript() }
        keepScriptDragsInEditor()
        pureBlackCheckBox.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(PREF_PURE_BLACK, checked).apply()
            // Recreating ends the warm connection, so setBusy() keeps this disabled during commands.
            if (isNightMode()) recreate()
        }
    }

    /** Let a script longer than the editor scroll inside it instead of moving the whole page. */
    @SuppressLint("ClickableViewAccessibility")
    private fun keepScriptDragsInEditor() {
        scriptEditor.setOnTouchListener { view, event ->
            // ScrollView clears this request on every new touch, so asking on ACTION_DOWN is enough.
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                (view.canScrollVertically(-1) || view.canScrollVertically(1))
            ) {
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
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

    /** Switch to the light's own white LEDs at the selected brightness, with both channels at full. */
    private fun runWhiteCommand() {
        val brightness = brightnessSeekBar.progress + 1
        val temperature = TelinkProtocol.WHITE_NEUTRAL_KELVIN
        runNormalCommand(
            TelinkProtocol.COMMAND_WHITE,
            TelinkProtocol.whiteParameters(temperature, brightness),
            "valkoinen $temperature K, kirkkaus $brightness %",
        )
    }

    private fun runNormalCommand(opcode: Int, parameters: ByteArray, description: String) {
        val group = selectedGroup()
        if (group != null) {
            runGroupProgram(listOf(LightCommand(1, description, opcode, parameters)), "Komento '$description'", group)
            return
        }
        val target = selectedLight() ?: return
        val currentClient = client ?: return
        if (!ensureBluetoothReady()) return
        if (operationJob?.isActive == true || scriptJob?.isActive == true) return

        val credentials = currentCredentials()
        cancelWarmConnection()
        releaseGroupClients()
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
                releaseGroupClients()
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
        val group = selectedGroup()
        if (group != null) {
            runGroupProgram(program, "Skripti", group)
        } else {
            runSingleProgram(program, "Skripti käynnissä.", "Skripti valmistui.", "Skripti pysäytettiin")
        }
    }

    /** Apply a stored scene to the selected light or group, validating it again first. */
    private fun applyScene(scene: Scene) {
        val program = try {
            library.program(scene)
        } catch (exception: IllegalArgumentException) {
            showError(exception)
            return
        }
        val group = selectedGroup()
        if (group != null) {
            runGroupProgram(program, "Kohtaus '${scene.name}'", group)
        } else {
            runSingleProgram(
                program,
                "Kohtaus '${scene.name}' käynnissä.",
                "Kohtauksen '${scene.name}' komennot kirjoitettiin. Fyysistä tilaa ei vahvistettu.",
                "Kohtaus '${scene.name}' pysäytettiin",
            )
        }
    }

    /** Run a validated program on the selected light, waiting for it if it is not reachable yet. */
    private fun runSingleProgram(program: List<ScriptStep>, started: String, finished: String, stopped: String) {
        val target = selectedLight() ?: return
        val currentClient = client ?: return
        if (!ensureBluetoothReady()) return
        if (operationJob?.isActive == true || scriptJob?.isActive == true) return

        val credentials = currentCredentials()
        cancelWarmConnection()
        releaseGroupClients()
        scriptJob = uiScope.launch {
            try {
                setBusy(true, scriptRunning = true)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                showStatus("$started Ensimmäinen yhteys avataan tarvittaessa…")
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
                showStatus(finished)
            } catch (exception: CancellationException) {
                showStatus("$stopped ja BLE-yhteys katkaistaan.")
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
            groupClients.values.toList().forEach { runCatching { it.disconnect() } }
        }
    }

    /**
     * Run [program] once for every light in [group], fanning out each command.
     *
     * Each command is written to every member that is still responding before
     * the next command or wait. A member that fails is reported and skipped for
     * the rest of the run; it is never retried, because an interrupted write has
     * an unknown outcome. Members are written one after another in a step.
     */
    private fun runGroupProgram(program: List<ScriptStep>, title: String, group: LightGroup) {
        val mainClient = client ?: return
        val adapter = bluetoothAdapter ?: return
        if (!ensureBluetoothReady()) return
        if (operationJob?.isActive == true || scriptJob?.isActive == true) return

        val credentials = currentCredentials()
        cancelWarmConnection()
        scriptJob = uiScope.launch {
            try {
                setBusy(true, scriptRunning = true)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Each member gets its own client; the single-light connection would duplicate one.
                mainClient.disconnect()
                releaseGroupClients(keep = group.members.toSet())
                locateGroupMembers(group)
                val runner = GroupRunner(
                    group.members,
                    connect = { member ->
                        val receipt = groupClient(member, adapter).connect(member, infoFor(member), credentials)
                        lightInfo[member] = receipt.info
                    },
                    send = { member, command ->
                        groupClient(member, adapter)
                            .send(member, infoFor(member), credentials, command.opcode, command.parameters)
                    },
                )
                var lastProgress = ""
                val results = runner.run(
                    program,
                    wait = { step ->
                        if (step.report) {
                            showStatus("$lastProgress\nRivi ${step.line}: odotetaan ${step.milliseconds} ms…")
                        }
                        delay(step.milliseconds)
                    },
                    onConnecting = { member ->
                        showStatus("$title ryhmälle '${group.name}': yhdistetään valoon ${lightName(member)}…")
                    },
                    onMemberFailed = { result -> showStatus("${lastProgress}\n${failureText(result)}".trim()) },
                    onCommand = { command, snapshot ->
                        if (command.report) {
                            lastProgress = commandProgress(command, snapshot)
                            showStatus(lastProgress)
                        }
                    },
                )
                showStatus(groupSummary(title, group, results))
            } catch (exception: CancellationException) {
                showStatus("$title pysäytettiin ja ryhmän BLE-yhteydet katkaistaan.")
            } catch (exception: Exception) {
                showError(exception)
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                scriptJob = null
                setBusy(false)
            }
        }
    }

    /**
     * Find several uncached members with one shared scan.
     *
     * Android silently stops scans started more than about five times in 30
     * seconds, so many members must not each start their own.
     */
    private suspend fun locateGroupMembers(group: LightGroup) {
        val missing = group.members.filter { infoFor(it) == null }
        val currentScanner = scanner ?: return
        if (missing.size <= MAX_INDIVIDUAL_MEMBER_SCANS) return
        showStatus("Etsitään ryhmän '${group.name}' ${missing.size} valoa yhdellä 8 sekunnin skannauksella…")
        try {
            currentScanner.scan().forEach { lightInfo[it.info.address] = it.info }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // Members still missing then scan for themselves while connecting.
        }
    }

    private fun groupClient(address: String, adapter: BluetoothAdapter): BleMeshClient =
        groupClients.getOrPut(address) { BleMeshClient(this, adapter) }

    /** Close group connections, except those to [keep]. */
    private fun releaseGroupClients(keep: Set<String> = emptySet()) {
        val released = groupClients.keys.filter { it !in keep }
        for (address in released) {
            groupClients.remove(address)?.shutdown()
        }
    }

    private fun infoFor(address: String): AdvertisementInfo? =
        lightInfo[address] ?: lightChoices.firstOrNull { it.address == address }?.info

    private fun lightName(address: String): String = library.displayName(address)

    private fun commandProgress(command: LightCommand, snapshot: List<MemberResult>): String {
        val written = snapshot.filter { !it.failed }
        return "Rivi ${command.line}: ${command.description} kirjoitettiin ${written.size}/${snapshot.size} " +
            "valolle (${written.joinToString { lightName(it.address) }.ifEmpty { "ei yhdellekään" }}). " +
            "Fyysistä tilaa ei vahvistettu."
    }

    private fun failureText(result: MemberResult): String {
        val name = lightName(result.address)
        val where = result.failedLine?.let { "rivillä $it" } ?: "yhdistettäessä"
        val reason = when (result.failure) {
            FailureKind.UNREACHABLE -> "ei tavoitettu $where"
            FailureKind.UNCERTAIN -> "lopputulos epävarma $where"
            else -> "virhe $where"
        }
        val before = if (result.commandsWritten > 0) " (aiemmin kirjoitettu ${result.commandsWritten} komentoa)" else ""
        val message = result.error?.message ?: result.error?.javaClass?.simpleName ?: ""
        return "• $name: $reason$before: $message. Valolle ei lähetetä enempää komentoja."
    }

    private fun groupSummary(title: String, group: LightGroup, results: List<MemberResult>): String = buildString {
        val written = results.filter { !it.failed && it.commandsWritten > 0 }
        val failed = results.filter { it.failed }
        append("$title ryhmälle '${group.name}' päättyi.")
        if (written.isEmpty()) {
            append("\nYksikään ryhmän valo ei kuitannut komentoja.")
        } else {
            append("\nKirjoitettu ${written.size}/${results.size} valolle: ")
            append(written.joinToString { "${lightName(it.address)} (${it.commandsWritten} komentoa)" })
            append(". Fyysistä tilaa ei vahvistettu.")
        }
        if (failed.isNotEmpty()) {
            append("\nEpäonnistui:")
            failed.forEach { append("\n").append(failureText(it)) }
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
        renameLightButton.isEnabled = enabled
        targetSpinner.isEnabled = enabled
        newGroupButton.isEnabled = enabled
        updateGroupButtons()
        for (index in 0 until sceneList.childCount) sceneList.getChildAt(index).isEnabled = enabled
        saveSceneButton.isEnabled = enabled
        meshNameInput.isEnabled = enabled
        passwordInput.isEnabled = enabled
        onButton.isEnabled = enabled
        offButton.isEnabled = enabled
        disconnectButton.isEnabled = enabled
        colorWheel.isEnabled = enabled
        brightnessSeekBar.isEnabled = enabled
        colorButton.isEnabled = enabled
        whiteButton.isEnabled = enabled
        scriptEditor.isEnabled = enabled
        loadCycleButton.isEnabled = enabled
        loadRainbowButton.isEnabled = enabled
        openScriptButton.isEnabled = enabled
        runScriptButton.isEnabled = enabled
        stopScriptButton.isEnabled = busy && scriptRunning
        pureBlackCheckBox.isEnabled = enabled
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
        whiteButton.isEnabled = enabled
        runScriptButton.isEnabled = enabled
        for (index in 0 until sceneList.childCount) sceneList.getChildAt(index).isEnabled = enabled
    }

    private fun showStatus(message: String) {
        statusText.text = message
    }

    private fun showError(exception: Throwable) {
        val prefix = when (exception) {
            is CommandOutcomeUnknownException -> "Komennon lopputulos jäi epävarmaksi"
            is TelinkProtocolException -> "Telink-protokollavirhe"
            is ScriptException -> "Skriptivirhe"
            is LibraryException -> "Tallennus epäonnistui"
            is BleMeshException -> "Bluetooth-virhe"
            else -> "Virhe"
        }
        showStatus("$prefix: ${exception.message ?: exception.javaClass.simpleName}")
    }

    private fun installDiscoveredLights(found: List<DiscoveredLight>) {
        found.forEach { lightInfo[it.info.address] = it.info }
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
        val advertised = choice.info?.displayLabel() ?: "BleMesh  ${choice.address}"
        val base = library.alias(choice.address)?.let { "$it · $advertised" } ?: advertised
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
        lightInfo[info.address] = info
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
        // While a group is the target, its members have their own connections.
        if (selectedGroup() != null) return
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
        pureBlackCheckBox.isChecked = preferences.getBoolean(PREF_PURE_BLACK, false)
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
            .putString(PREF_TARGET_GROUP, selectedGroup()?.name)
            .apply()
    }

    /** The group chosen in [targetSpinner], or null when commands go to the selected light. */
    private fun selectedGroup(): LightGroup? = targetGroups.getOrNull(targetSpinner.selectedItemPosition - 1)

    private fun refreshTargetSpinner(selectName: String?) {
        targetGroups = library.groups
        val labels = listOf("Valittu valo") + targetGroups.map { "Ryhmä ${it.name} (${it.members.size} valoa)" }
        val index = selectName
            ?.let { name -> targetGroups.indexOfFirst { LightNames.sameName(it.name, name) } }
            ?.takeIf { it >= 0 }
            ?.plus(1)
            ?: 0
        // Programmatic selection must not look like the user picking a target.
        ignoreTargetCallback = true
        targetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        targetSpinner.setSelection(index)
        updateGroupButtons()
        targetSpinner.post { ignoreTargetCallback = false }
    }

    private fun updateGroupButtons() {
        val hasGroup = selectedGroup() != null
        editGroupButton.isEnabled = !busy && hasGroup
        deleteGroupButton.isEnabled = !busy && hasGroup
    }

    /** One button per scene: a tap applies it, a long press offers rename, editor, and delete. */
    private fun refreshSceneList() {
        sceneList.removeAllViews()
        val scenes = library.scenes
        for (scene in scenes) {
            val button = Button(this).apply {
                text = scene.name
                isEnabled = !busy && client != null
                contentDescription = "Käytä kohtausta ${scene.name} valitulle kohteelle"
                setOnClickListener { applyScene(scene) }
                setOnLongClickListener {
                    showSceneActions(scene)
                    true
                }
            }
            sceneList.addView(
                button,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        noScenesText.visibility = if (scenes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showRenameLightDialog() {
        val light = selectedLight() ?: return
        val input = nameInput(R.string.light_name_hint, library.alias(light.address))
        showForm("Nimeä valo ${light.address}", dialogContent(input, helpText(R.string.name_rule_help)), input) {
            library.setAlias(light.address, input.text.toString())
            refreshLightSpinner()
            refreshTargetSpinner(selectedGroup()?.name)
            library.alias(light.address)
                ?.let { "Valo ${light.address} on nyt '$it'. Valolle ei lähetetty mitään." }
                ?: "Valon ${light.address} nimi poistettiin. Valolle ei lähetetty mitään."
        }
    }

    private fun showGroupDialog(existing: LightGroup?) {
        // Lights the app knows about: named lights, group members, and lights seen this session.
        val candidates = (library.knownAddresses + lightChoices.map { it.address } + lightInfo.keys)
            .distinct()
            .sortedWith(compareBy({ library.alias(it) == null }, { LightNames.key(lightName(it)) }))
        if (candidates.isEmpty()) {
            showStatus("Skannaa ensin valot, jotta ne voi lisätä ryhmään.")
            return
        }
        val input = nameInput(R.string.group_name_hint, existing?.name)
        val boxes = candidates.map { address ->
            CheckBox(this).apply {
                text = library.alias(address)?.let { "$it · $address" } ?: address
                isChecked = existing?.members?.contains(address) == true
                minHeight = dp(48)
            }
        }
        val content = dialogContent(
            input,
            helpText(R.string.name_rule_help),
            helpText(R.string.group_members),
            *boxes.toTypedArray(),
        )
        val title = existing?.let { "Muokkaa ryhmää '${it.name}'" } ?: getString(R.string.new_group).trimEnd('…')
        showForm(title, content, input) {
            val members = candidates.filterIndexed { index, _ -> boxes[index].isChecked }
            val group = library.saveGroup(
                input.text.toString(),
                members,
                discoveredAddresses = candidates.toSet(),
                replacing = existing?.name,
            )
            refreshTargetSpinner(group.name)
            "Ryhmä '${group.name}' tallennettiin ja valittiin kohteeksi: ${group.members.joinToString { lightName(it) }}. " +
                "Valoihin ei lähetetty mitään."
        }
    }

    private fun confirmDeleteGroup(group: LightGroup) {
        AlertDialog.Builder(this)
            .setTitle("Poistetaanko ryhmä '${group.name}'?")
            .setMessage("Valot ja niiden nimet säilyvät. Valoihin ei lähetetä mitään.")
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    library.deleteGroup(group.name)
                } catch (exception: LibraryException) {
                    showError(exception)
                    return@setPositiveButton
                }
                releaseGroupClients()
                refreshTargetSpinner(null)
                showStatus("Ryhmä '${group.name}' poistettiin. Kohteena on nyt valittu valo.")
                startWarmConnection()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSaveSceneDialog() {
        val rgb = colorWheel.selectedRgb
        val brightness = brightnessSeekBar.progress + 1
        val white = TelinkProtocol.WHITE_NEUTRAL_KELVIN
        val options: List<Pair<String, () -> String>> = listOf(
            "Väri #${hex(rgb.red)}${hex(rgb.green)}${hex(rgb.blue)}, kirkkaus $brightness %" to {
                SceneText.fromControls(SceneControls.Colour(rgb.red, rgb.green, rgb.blue, brightness))
            },
            "Valkoinen $white K, kirkkaus $brightness %" to {
                SceneText.fromControls(SceneControls.White(white, brightness))
            },
            "Päälle" to { SceneText.fromControls(SceneControls.On) },
            "Pois" to { SceneText.fromControls(SceneControls.Off) },
            "Editorin skripti" to { scriptEditor.text.toString() },
        )
        val choices = RadioGroup(this)
        val buttons = options.map { (label, _) ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                minHeight = dp(48)
            }.also { choices.addView(it) }
        }
        choices.check(buttons.first().id)
        val input = nameInput(R.string.scene_name_hint, null)
        showForm(getString(R.string.save_scene).trimEnd('…'), dialogContent(input, helpText(R.string.scene_from), choices), input) {
            val selected = buttons.indexOfFirst { it.isChecked }.coerceAtLeast(0)
            val scene = library.saveScene(input.text.toString(), options[selected].second())
            refreshSceneList()
            "Kohtaus '${scene.name}' tallennettiin. Sitä ei ole vielä ajettu."
        }
    }

    private fun showSceneActions(scene: Scene) {
        AlertDialog.Builder(this)
            .setTitle("Kohtaus '${scene.name}'")
            .setItems(arrayOf("Käytä", "Nimeä uudelleen…", "Avaa editoriin", "Poista…")) { _, which ->
                when (which) {
                    0 -> applyScene(scene)
                    1 -> showRenameSceneDialog(scene)
                    2 -> {
                        scriptEditor.setText(scene.script)
                        showStatus("Kohtaus '${scene.name}' avattiin editoriin. Sitä ei ole ajettu.")
                    }
                    else -> confirmDeleteScene(scene)
                }
            }
            .show()
    }

    private fun showRenameSceneDialog(scene: Scene) {
        val input = nameInput(R.string.scene_name_hint, scene.name)
        showForm("Nimeä kohtaus '${scene.name}'", dialogContent(input), input) {
            val renamed = library.renameScene(scene.name, input.text.toString())
            refreshSceneList()
            "Kohtaus '${scene.name}' on nyt '${renamed.name}'."
        }
    }

    private fun confirmDeleteScene(scene: Scene) {
        AlertDialog.Builder(this)
            .setTitle("Poistetaanko kohtaus '${scene.name}'?")
            .setMessage("Valoihin ei lähetetä mitään.")
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    library.deleteScene(scene.name)
                    refreshSceneList()
                    showStatus("Kohtaus '${scene.name}' poistettiin.")
                } catch (exception: LibraryException) {
                    showError(exception)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Show a dialog that stays open until [save] succeeds.
     *
     * A validation error is shown on [input]; [save] returns the status message.
     */
    private fun showForm(title: String, content: View, input: EditText, save: () -> String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val message = save()
                    dialog.dismiss()
                    showStatus(message)
                } catch (exception: IllegalArgumentException) {
                    input.error = exception.message ?: exception.javaClass.simpleName
                }
            }
        }
        dialog.show()
    }

    private fun dialogContent(vararg views: View): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        views.forEach { column.addView(it) }
        return ScrollView(this).apply { addView(column) }
    }

    private fun nameInput(hint: Int, text: String?): EditText = EditText(this).apply {
        setHint(hint)
        setText(text.orEmpty())
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(InputFilter.LengthFilter(LightNames.MAX_NAME_LENGTH))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun helpText(text: Int): TextView = TextView(this).apply {
        setText(text)
        textSize = 13f
        setTextColor(getColor(R.color.text_secondary))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun hex(value: Int): String = value.toString(16).padStart(2, '0').lowercase(Locale.ROOT)

    private companion object {
        const val MAX_SCRIPT_CHARACTERS = 262_144
        /** Up to this many uncached group members may each scan for themselves. */
        const val MAX_INDIVIDUAL_MEMBER_SCANS = 3
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
        const val PREF_PURE_BLACK = "pure_black"
        const val PREF_SCRIPT = "script"
        const val PREF_TARGET_GROUP = "target_group"
        const val REQUEST_BLUETOOTH_PERMISSIONS = 100
        const val REQUEST_ENABLE_BLUETOOTH = 101
        const val REQUEST_OPEN_SCRIPT = 102
    }
}
