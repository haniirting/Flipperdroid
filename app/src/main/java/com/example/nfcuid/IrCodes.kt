package com.example.nfcuid

// Infrared encoders for the protocols the Flipper firmware (dev branch, lib/infrared/encoder_decoder) uses.
// Timings and bit layouts follow the firmware sources: NEC, NECext, Samsung32, RC5, SIRC / SIRC15 / SIRC20.
object IrCodes {

    val protocols = listOf("NEC", "NECext", "Samsung32", "RC5", "SIRC", "SIRC15", "SIRC20")

    class Signal(val carrier: Int, val pattern: IntArray)

    private var rc5Toggle = 0

    // preamble, then data bits LSB first (bit1 / bit0 mark+space pairs), optional stop mark
    private fun pulseDistance(
        preMark: Int, preSpace: Int,
        m1: Int, s1: Int, m0: Int, s0: Int,
        data: Long, nbits: Int, stopMark: Int
    ): ArrayList<Int> {
        val p = ArrayList<Int>()
        p.add(preMark)
        p.add(preSpace)
        for (i in 0 until nbits) {
            if (((data shr i) and 1L) == 1L) {
                p.add(m1); p.add(s1)
            } else {
                p.add(m0); p.add(s0)
            }
        }
        if (stopMark > 0) p.add(stopMark)
        return p
    }

    // RC5 Manchester: every bit = two halves; 1 = space then mark, 0 = mark then space
    private fun manchester(bits: List<Int>, half: Int): IntArray {
        val levels = ArrayList<Boolean>()
        for (b in bits) {
            if (b == 1) { levels.add(false); levels.add(true) } else { levels.add(true); levels.add(false) }
        }
        val runs = ArrayList<Int>()
        var cur = levels[0]
        var len = half
        for (i in 1 until levels.size) {
            if (levels[i] == cur) {
                len += half
            } else {
                runs.add(len)
                cur = levels[i]
                len = half
            }
        }
        runs.add(len)
        if (!levels[0]) runs.removeAt(0)                    // pattern must start with a mark
        if (!levels[levels.size - 1] && runs.isNotEmpty()) runs.removeAt(runs.size - 1)  // and end with a mark
        return runs.toIntArray()
    }

    private fun sirc(address: Int, command: Int, nbits: Int, addrMask: Int): Signal {
        val data = (command and 0x7F).toLong() or ((address and addrMask).toLong() shl 7)
        val all = ArrayList<Int>()
        for (r in 0 until 3) {                              // SIRC frames are sent 3 times, 45 ms period
            val f = pulseDistance(2400, 600, 1200, 600, 600, 600, data, nbits, 0)
            if (r < 2) {
                val sum = f.sum()
                f[f.size - 1] = f[f.size - 1] + (45000 - sum).coerceAtLeast(0)
            } else {
                f.removeAt(f.size - 1)
            }
            all.addAll(f)
        }
        return Signal(40000, all.toIntArray())
    }

    fun build(protocol: String, address: Int, command: Int): Signal? {
        return when (protocol) {
            "NEC" -> {
                val a = address and 0xFF
                val c = command and 0xFF
                val data = a.toLong() or ((a.inv() and 0xFF).toLong() shl 8) or
                    (c.toLong() shl 16) or ((c.inv() and 0xFF).toLong() shl 24)
                Signal(38000, pulseDistance(9000, 4500, 560, 1690, 560, 560, data, 32, 560).toIntArray())
            }
            "NECext" -> {
                val data = (address and 0xFFFF).toLong() or ((command and 0xFFFF).toLong() shl 16)
                Signal(38000, pulseDistance(9000, 4500, 560, 1690, 560, 560, data, 32, 560).toIntArray())
            }
            "Samsung32" -> {
                val a = address and 0xFF
                val c = command and 0xFF
                val data = a.toLong() or (a.toLong() shl 8) or (c.toLong() shl 16) or ((c.inv() and 0xFF).toLong() shl 24)
                Signal(38000, pulseDistance(4500, 4500, 550, 1650, 550, 550, data, 32, 550).toIntArray())
            }
            "RC5" -> {
                val a = address and 0x1F
                val c = command and 0x3F
                rc5Toggle = rc5Toggle xor 1
                val bits = ArrayList<Int>()
                bits.add(1); bits.add(1); bits.add(rc5Toggle)
                for (i in 4 downTo 0) bits.add((a shr i) and 1)
                for (i in 5 downTo 0) bits.add((c shr i) and 1)
                Signal(36000, manchester(bits, 888))
            }
            "SIRC" -> sirc(address, command, 12, 0x1F)
            "SIRC15" -> sirc(address, command, 15, 0xFF)
            "SIRC20" -> sirc(address, command, 20, 0x1FFF)
            else -> null
        }
    }
}
