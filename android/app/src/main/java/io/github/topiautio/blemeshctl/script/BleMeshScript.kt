package io.github.topiautio.blemeshctl.script

import io.github.topiautio.blemeshctl.protocol.TelinkProtocol
import io.github.topiautio.blemeshctl.protocol.TelinkProtocolException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/** Raised when a light-control script cannot be read or validated. */
class ScriptException(message: String) : IllegalArgumentException(message)

sealed interface ScriptStep {
    val line: Int
}

/** One validated normal light command with its source line. */
data class LightCommand(
    override val line: Int,
    val description: String,
    val opcode: Int,
    val parameters: ByteArray,
    val report: Boolean = true,
) : ScriptStep

/** One positive script delay with its source line. */
data class WaitStep(
    override val line: Int,
    val milliseconds: Long,
    val report: Boolean = true,
) : ScriptStep

/** One complete, fully saturated RGB hue sweep with a dwell per colour. */
data class RainbowStep(override val line: Int, val milliseconds: Long) : ScriptStep

/** A finite or endless block of script steps. */
data class LoopStep(override val line: Int, val count: Int?, val body: List<ScriptStep>) : ScriptStep

/**
 * Parser and executor for the same `.blemesh` language as the command-line tool.
 *
 * The format intentionally exposes only ordinary on, off, and RGB controls. It
 * never evaluates code, raw Telink packets, or other arbitrary input.
 */
object BleMeshScript {
    private val WHITESPACE = Regex("\\s+")

    /** Every distinct fully saturated 8-bit RGB hue in wheel order. */
    val rainbowColors: List<Triple<Int, Int, Int>> = buildList(1530) {
        for (value in 0 until 255) add(Triple(255, value, 0))
        for (value in 0 until 255) add(Triple(255 - value, 255, 0))
        for (value in 0 until 255) add(Triple(0, 255, value))
        for (value in 0 until 255) add(Triple(0, 255 - value, 255))
        for (value in 0 until 255) add(Triple(value, 0, 255))
        for (value in 0 until 255) add(Triple(255, 0, 255 - value))
    }

    /** Parse and validate an entire script before it can send any command. */
    fun parse(text: String, source: String = "<script>"): List<ScriptStep> = Parser(text, source).parse()

    /**
     * Run a previously validated program in source order.
     *
     * Errors from [sendCommand] and cancellation from [wait] deliberately
     * propagate: a failed Bluetooth write has an unknown outcome, so retrying it
     * automatically could make the light do something unexpected.
     */
    suspend fun execute(
        program: List<ScriptStep>,
        sendCommand: suspend (LightCommand) -> Unit,
        wait: suspend (WaitStep) -> Unit,
    ) {
        suspend fun executeSteps(steps: List<ScriptStep>) {
            for (step in steps) {
                when (step) {
                    is LightCommand -> sendCommand(step)
                    is WaitStep -> wait(step)
                    is RainbowStep -> rainbowColors.forEachIndexed { index, (red, green, blue) ->
                        sendCommand(
                            LightCommand(
                                step.line,
                                "rainbow colour rgb #${red.hex()}${green.hex()}${blue.hex()}",
                                TelinkProtocol.COMMAND_RGB,
                                TelinkProtocol.rgbParameters(red, green, blue, 100),
                                report = index == 0,
                            ),
                        )
                        wait(WaitStep(step.line, step.milliseconds, report = false))
                    }
                    is LoopStep -> {
                        val count = step.count
                        if (count == null) {
                            while (true) {
                                executeSteps(step.body)
                            }
                        } else {
                            repeat(count) { executeSteps(step.body) }
                        }
                    }
                }
            }
        }

        executeSteps(program)
    }

    private data class SourceLine(val number: Int, val indentation: Int, val text: String)

    private data class ParseResult(val steps: List<ScriptStep>, val nextIndex: Int)

    private class Parser(text: String, private val source: String) {
        private val lines = sourceLines(text)

        fun parse(): List<ScriptStep> {
            if (lines.isEmpty()) {
                throw ScriptException("$source: script contains no instructions")
            }
            if (lines.first().indentation != 0) {
                throw error(lines.first(), "the first instruction must not be indented")
            }
            val result = parseBlock(0, 0)
            if (result.nextIndex != lines.size) {
                throw error(lines[result.nextIndex], "unexpected indentation")
            }
            if (!containsLightCommand(result.steps)) {
                throw ScriptException("$source: script contains no light commands")
            }
            validateLoops(result.steps)
            return result.steps
        }

        private fun sourceLines(text: String): List<SourceLine> = buildList {
            text.splitToSequence('\n').forEachIndexed { index, rawLine ->
                val raw = rawLine.removeSuffix("\r")
                val contentStart = raw.indexOfFirst { it != ' ' && it != '\t' }
                if (contentStart < 0) return@forEachIndexed
                val content = raw.substring(contentStart).trimEnd()
                if (content.startsWith("#")) return@forEachIndexed

                val indentation = raw.substring(0, contentStart)
                val line = SourceLine(index + 1, indentation.length, content)
                if ('\t' in indentation) {
                    throw error(line.copy(indentation = 0), "tabs are not supported for indentation")
                }
                add(line)
            }
        }

        private fun parseBlock(startIndex: Int, indentation: Int): ParseResult {
            val steps = mutableListOf<ScriptStep>()
            var index = startIndex
            while (index < lines.size) {
                val line = lines[index]
                if (line.indentation < indentation) break
                if (line.indentation > indentation) {
                    throw error(line, "unexpected indentation; indent only below a loop or repeat block")
                }

                val (isLoop, count) = parseLoopHeader(line)
                if (!isLoop) {
                    steps += parseInstruction(line)
                    index++
                    continue
                }

                index++
                if (index == lines.size || lines[index].indentation <= indentation) {
                    throw error(line, "loop or repeat block needs an indented body")
                }
                val body = parseBlock(index, lines[index].indentation)
                steps += LoopStep(line.number, count, body.steps)
                index = body.nextIndex
            }
            return ParseResult(steps, index)
        }

        private fun parseLoopHeader(line: SourceLine): Pair<Boolean, Int?> {
            if (line.text == "loop:") return true to null
            if (line.text == "loop" || line.text.startsWith("loop ")) {
                throw error(line, "loop blocks must be written as 'loop:'")
            }
            if (line.text == "repeat:" || line.text.startsWith("repeat ")) {
                if (!line.text.endsWith(":")) {
                    throw error(line, "repeat blocks must be written as 'repeat N:'")
                }
                val words = line.text.dropLast(1).split(WHITESPACE)
                if (words.size != 2) {
                    throw error(line, "repeat requires one positive integer count")
                }
                val countText = words[1]
                val count = countText.toIntOrNull()
                if (count == null || count <= 0 || !countText.all { it in '0'..'9' }) {
                    throw error(line, "repeat count must be a positive integer")
                }
                return true to count
            }
            return false to null
        }

        private fun parseInstruction(line: SourceLine): ScriptStep {
            val words = line.text.split(WHITESPACE)
            return when (val instruction = words.first()) {
                "on" -> {
                    if (words.size != 1) throw error(line, "on does not take arguments")
                    LightCommand(line.number, "on", TelinkProtocol.COMMAND_ON_OFF, TelinkProtocol.onParameters())
                }
                "off" -> {
                    if (words.size != 1) throw error(line, "off does not take arguments")
                    LightCommand(line.number, "off", TelinkProtocol.COMMAND_ON_OFF, TelinkProtocol.offParameters())
                }
                "color" -> parseColor(line, words)
                "wait" -> {
                    if (words.size != 2) throw error(line, "wait requires one positive duration")
                    WaitStep(line.number, parseDuration(words[1], line))
                }
                "rainbow" -> {
                    if (words.size != 2) throw error(line, "rainbow requires one positive duration per colour")
                    RainbowStep(line.number, parseDuration(words[1], line))
                }
                "loop", "repeat" -> throw error(line, "$instruction blocks must end with ':'")
                else -> {
                    if (line.text.endsWith(":")) {
                        throw error(line, "only 'loop:' and 'repeat N:' can introduce a block")
                    }
                    throw error(line, "unknown instruction '$instruction'")
                }
            }
        }

        private fun parseColor(line: SourceLine, words: List<String>): LightCommand {
            if (words.size !in 2..3) {
                throw error(line, "color requires RRGGBB and optional brightness from 1 through 100")
            }
            val rgb = try {
                TelinkProtocol.parseRgb(words[1])
            } catch (exception: TelinkProtocolException) {
                throw error(line, exception.message ?: "invalid RGB colour")
            }
            val brightness = if (words.size == 3) {
                words[2].toIntOrNull() ?: throw error(line, "brightness must be an integer from 1 through 100")
            } else {
                100
            }
            val parameters = try {
                TelinkProtocol.rgbParameters(rgb.red, rgb.green, rgb.blue, brightness)
            } catch (exception: TelinkProtocolException) {
                throw error(line, exception.message ?: "invalid RGB colour")
            }
            return LightCommand(
                line.number,
                "colour rgb #${rgb.red.hex()}${rgb.green.hex()}${rgb.blue.hex()}, brightness $brightness",
                TelinkProtocol.COMMAND_RGB,
                parameters,
            )
        }

        /** Return a positive duration in whole milliseconds, rounding fractions up. */
        private fun parseDuration(value: String, line: SourceLine): Long {
            var number = value.lowercase(Locale.ROOT)
            var multiplier = 1000.0
            for ((suffix, suffixMultiplier) in listOf("ms" to 1.0, "s" to 1000.0, "m" to 60_000.0)) {
                if (number.endsWith(suffix)) {
                    number = number.dropLast(suffix.length)
                    multiplier = suffixMultiplier
                    break
                }
            }
            val amount = number.toDoubleOrNull()
                ?: throw error(line, "wait must be a positive duration such as 250ms, 1s, or 2m")
            val milliseconds = amount * multiplier
            if (!(abs(milliseconds) <= Double.MAX_VALUE) || milliseconds <= 0.0 || milliseconds > Long.MAX_VALUE.toDouble()) {
                throw error(line, "wait must be a positive, finite duration")
            }
            return ceil(milliseconds).toLong().coerceAtLeast(1)
        }

        private fun containsLightCommand(steps: List<ScriptStep>): Boolean = steps.any { step ->
            step is LightCommand || step is RainbowStep || (step is LoopStep && containsLightCommand(step.body))
        }

        private fun containsDelay(steps: List<ScriptStep>): Boolean = steps.any { step ->
            step is WaitStep || step is RainbowStep || (step is LoopStep && containsDelay(step.body))
        }

        private fun validateLoops(steps: List<ScriptStep>) {
            for (step in steps) {
                if (step !is LoopStep) continue
                validateLoops(step.body)
                if (step.count == null && !containsDelay(step.body)) {
                    throw ScriptException("$source:${step.line}: an endless loop needs at least one wait command")
                }
            }
        }

        private fun error(line: SourceLine, message: String): ScriptException =
            ScriptException("$source:${line.number}: $message")
    }
}

private fun Int.hex(): String = toString(16).padStart(2, '0')
