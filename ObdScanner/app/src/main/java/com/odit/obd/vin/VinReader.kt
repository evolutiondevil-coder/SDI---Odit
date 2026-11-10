package com.odit.obd.vin

import com.odit.obd.elm.ElmTimeoutException
import com.odit.obd.elm.ObdProtocol
import com.odit.obd.obd.ObdSession
import com.odit.obd.uds.DiagType
import com.odit.obd.uds.FoundModule
import com.odit.obd.uds.ModuleScanner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** All VINs found and the one chosen to identify the car. */
data class VinReport(val vin: String?, val found: List<VinFound>) {
    val distinct: List<String> get() = found.map { it.vin }.distinct()

    /** Different modules report different VINs: usually a module taken from another car. */
    val conflict: Boolean get() = distinct.size > 1

    companion object {
        val EMPTY = VinReport(null, emptyList())
    }
}

/**
 * Reads the VIN through a chain of sources, stopping at the first that works:
 * 1. OBD mode 09 PID 02 (all control units, functional request);
 * 2. CAN: UDS 22 F190 physically addressed to the engine, then the gearbox, then modules found by a
 *    module scan (KWP2000 modules get 1A 90);
 * 3. K-line KWP2000: 1A 90 to the engine (address 0x10).
 * VINs that modules reported during a module scan are added so that a mismatch can be shown.
 */
class VinReader(private val s: ObdSession) {

    suspend fun read(knownModules: List<FoundModule> = emptyList()): VinReport {
        val found = mutableListOf<VinFound>()
        try {
            found += VinParser.parse0902(s.request("0902", 6_000).lines, s.protocol)
        } catch (_: ElmTimeoutException) {
        }

        if (found.isEmpty()) {
            when {
                s.protocol.isCan -> {
                    val targets = mutableListOf<ModuleScanner.VinTarget>()
                    if (s.protocol.is29bit) {
                        targets += ModuleScanner.VinTarget(0x10, 0, DiagType.UDS)
                        targets += ModuleScanner.VinTarget(0x18, 0, DiagType.UDS)
                    } else {
                        targets += ModuleScanner.VinTarget(0x7E0, 0x7E8, DiagType.UDS)
                        targets += ModuleScanner.VinTarget(0x7E1, 0x7E9, DiagType.UDS)
                    }
                    for (m in knownModules) {
                        val type = m.type ?: continue
                        if (targets.none { it.tx == m.tx }) targets += ModuleScanner.VinTarget(m.tx, m.rx, type)
                    }
                    found += ModuleScanner(s).readVins(targets, stopAtFirst = true)
                }
                s.protocol == ObdProtocol.KWP_FAST || s.protocol == ObdProtocol.KWP_SLOW -> found += kLineVin()
                else -> Unit
            }
        }

        return withModules(VinReport(choose(found), found), knownModules)
    }

    private suspend fun kLineVin(): List<VinFound> {
        s.adapterDirty = true
        return try {
            // Physical addressing to the engine (0x10) from the tester (0xF1); the ELM inserts the length.
            s.elm.send("ATSH8110F1", 2_000)
            val reply = s.rawRequest("1A90", 5_000)
            reply.messages.mapNotNull { m -> VinParser.parseKwp1A90(m.data)?.let { VinFound(it, m.header, VinMethod.KWP_1A90) } }
        } catch (_: ElmTimeoutException) {
            emptyList()
        } finally {
            withContext(NonCancellable) { runCatching { s.restoreObdDefaults() } }
        }
    }

    companion object {
        /** Adds the VINs that modules reported during a module scan (no adapter traffic). */
        fun withModules(report: VinReport, modules: List<FoundModule>): VinReport {
            val found = report.found.toMutableList()
            for (m in modules) {
                val v = m.vin ?: continue
                if (found.none { it.ecu == m.responseHeader && it.vin == v }) {
                    val method = when (m.type) {
                        DiagType.KWP -> VinMethod.KWP_1A90
                        DiagType.KLINE -> VinMethod.KLINE
                        else -> VinMethod.UDS_F190
                    }
                    found += VinFound(v, m.responseHeader, method)
                }
            }
            return VinReport(choose(found), found)
        }

        /** Prefer the engine's OBD answer, then any OBD answer, then the engine's UDS answer. */
        fun choose(found: List<VinFound>): String? =
            (
                found.firstOrNull { it.method == VinMethod.OBD_0902 && ObdSession.isEngine(it.ecu) }
                    ?: found.firstOrNull { it.method == VinMethod.OBD_0902 }
                    ?: found.firstOrNull { ObdSession.isEngine(it.ecu) }
                    ?: found.firstOrNull()
                )?.vin
    }
}
