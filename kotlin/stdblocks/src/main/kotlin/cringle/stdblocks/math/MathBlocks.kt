// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.math

import cringle.stdblocks.DOUBLE
import cringle.stdblocks.Entry
import cringle.stdblocks.INT
import cringle.stdblocks.asDouble
import cringle.stdblocks.asLong
import cringle.stdblocks.handlerEntry
import cringle.stdblocks.inPort
import cringle.stdblocks.latest2Entry
import cringle.stdblocks.long
import cringle.stdblocks.outPort
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/** A block with two inputs that sends `f(a, b)`. */
private fun binary(name: String, f: (Double, Double) -> Double) =
    latest2Entry(name, DOUBLE, listOf(outPort("out", DOUBLE))) { _ -> { a, b, emit -> emit.send("out", f(a.asDouble(), b.asDouble())) } }

/** A block with two inputs that sends `f(a, b)`, or `"division by zero"` on `error` when `b` is zero. */
private fun divisor(name: String, f: (Double, Double) -> Double) =
    latest2Entry(name, DOUBLE, listOf(outPort("out", DOUBLE), outPort("error"))) { _ ->
        { a, b, emit ->
            if (b.asDouble() == 0.0) emit.send("error", "division by zero") else emit.send("out", f(a.asDouble(), b.asDouble()))
        }
    }

private fun unary(name: String, f: (Double) -> Double) =
    handlerEntry(name, listOf(inPort("in", DOUBLE), outPort("out", DOUBLE))) { _ -> { _, v, emit -> emit.send("out", f(v.asDouble())) } }

internal val MATH_ENTRIES: List<Entry> = listOf(
    handlerEntry("math.constant", listOf(inPort("trigger", INT), outPort("out", DOUBLE)), "MathConstantConfig") { config ->
        val value = (config["value"] as? Number)?.toDouble() ?: throw IllegalArgumentException("the configuration 'value' is missing")
        require(value.isFinite()) { "the configuration 'value' must be a finite number" }
        return@handlerEntry { _, _, emit -> emit.send("out", value) }
    },
    binary("math.add") { a, b -> a + b },
    binary("math.subtract") { a, b -> a - b },
    binary("math.multiply") { a, b -> a * b },
    divisor("math.divide") { a, b -> a / b },
    divisor("math.modulo") { a, b -> a % b },
    binary("math.min") { a, b -> minOf(a, b) },
    binary("math.max") { a, b -> maxOf(a, b) },
    unary("math.abs") { kotlin.math.abs(it) },
    unary("math.negate") { -it },
    handlerEntry("math.round", listOf(inPort("in", DOUBLE), outPort("out", DOUBLE)), "RoundConfig") { config ->
        val decimals = config.long("decimals")?.toInt() ?: 0
        require(decimals in 0..15) { "the configuration 'decimals' is between 0 and 15" }
        return@handlerEntry { _, v, emit ->
            val x = v.asDouble()
            emit.send("out", if (x.isFinite()) BigDecimal(x.toString()).setScale(decimals, RoundingMode.HALF_UP).toDouble() else x)
        }
    },
    unary("math.floor") { kotlin.math.floor(it) },
    unary("math.ceil") { kotlin.math.ceil(it) },
    handlerEntry("math.random", listOf(inPort("trigger", INT), outPort("out", DOUBLE)), "RandomConfig") { config ->
        val min = (config["min"] as? Number)?.toDouble() ?: 0.0
        val max = (config["max"] as? Number)?.toDouble() ?: 1.0
        require(min < max) { "the configuration 'min' must be less than 'max'" }
        return@handlerEntry { _, _, emit -> emit.send("out", min + Random.nextDouble() * (max - min)) }
    },
    handlerEntry("math.counter", listOf(inPort("in", INT), inPort("reset", INT), outPort("count", INT)), "CounterConfig") { config ->
        val start = config.long("start") ?: 0L
        val step = config.long("step") ?: 1L
        var value = start
        return@handlerEntry { port, _, emit ->
            if (port == "reset") {
                value = start
            } else {
                value += step
                emit.send("count", value)
            }
        }
    },
    handlerEntry("math.accumulator", listOf(inPort("in", DOUBLE), inPort("reset", INT), outPort("sum", DOUBLE))) { _ ->
        var sum = 0.0
        return@handlerEntry { port, v, emit ->
            if (port == "reset") {
                sum = 0.0
            } else {
                sum += v.asDouble()
                emit.send("sum", sum)
            }
        }
    },
    handlerEntry("math.int-to-double", listOf(inPort("in", INT), outPort("out", DOUBLE))) { _ -> { _, v, emit -> emit.send("out", v.asLong().toDouble()) } },
    handlerEntry("math.to-int", listOf(inPort("in", DOUBLE), outPort("out", INT), outPort("error", cringle.stdblocks.STRING))) { _ ->
        { _, v, emit ->
            val x = v.asDouble()
            if (x.isFinite() && kotlin.math.abs(x) < 9.0e18) emit.send("out", Math.round(x)) else emit.send("error", "out of the range of an integer: $x")
        }
    },
)
