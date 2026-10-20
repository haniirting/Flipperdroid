package com.example.nfcuid

// Infrared encoders for the protocols the Flipper firmware (dev branch, lib/infrared/encoder_decoder) uses.
// Timings and bit layouts follow the firmware sources: NEC, NECext, Samsung32, RC5, SIRC / SIRC15 / SIRC20.
object IrCodes {

    val protocols = listOf(
        "NEC", "NECext", "NEC42", "NEC42ext", "Samsung32", "RC5", "RC5X", "RC6",
        "SIRC", "SIRC15", "SIRC20", "Kaseikyo", "Pioneer", "RCA"
    )

    class Signal(val carrier: Int, val pattern: IntArray)

    private var rc5Toggle = 0
    private var rc6Toggle = 0

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

    // (level, duration) runs -> alternating mark/space pattern starting with a mark and ending with a mark
    private fun runsToPattern(levels: List<Pair<Boolean, Int>>): IntArray {
        val runs = ArrayList<Pair<Boolean, Int>>()
        for (l in levels) {
            if (runs.isNotEmpty() && runs[runs.size - 1].first == l.first) {
                runs[runs.size - 1] = Pair(l.first, runs[runs.size - 1].second + l.second)
            } else {
                runs.add(l)
            }
        }
        while (runs.isNotEmpty() && !runs[0].first) runs.removeAt(0)
        while (runs.isNotEmpty() && !runs[runs.size - 1].first) runs.removeAt(runs.size - 1)
        return runs.map { it.second }.toIntArray()
    }

    private fun rev8(v: Int): Int {
        var r = 0
        for (i in 0 until 8) if (((v shr i) and 1) == 1) r = r or (1 shl (7 - i))
        return r
    }

    // RC6 mode 0: leader 2666/889, then Manchester halves of 444 us (1 = mark+space, 0 = space+mark), toggle bit is twice as long
    private fun rc6(address: Int, command: Int): Signal {
        rc6Toggle = rc6Toggle xor 1
        val bits = ArrayList<Int>()
        bits.add(1); bits.add(0); bits.add(0); bits.add(0); bits.add(rc6Toggle)
        for (i in 7 downTo 0) bits.add((address shr i) and 1)
        for (i in 7 downTo 0) bits.add((command shr i) and 1)
        val levels = ArrayList<Pair<Boolean, Int>>()
        levels.add(Pair(true, 2666)); levels.add(Pair(false, 889))
        for ((idx, b) in bits.withIndex()) {
            val half = if (idx == 4) 888 else 444
            if (b == 1) { levels.add(Pair(true, half)); levels.add(Pair(false, half)) }
            else { levels.add(Pair(false, half)); levels.add(Pair(true, half)) }
        }
        return Signal(36000, runsToPattern(levels))
    }

    private fun twice(frame: ArrayList<Int>, gap: Int): IntArray {
        val all = ArrayList<Int>()
        val f1 = ArrayList(frame)
        f1[f1.size - 1] = gap
        all.addAll(f1)
        all.addAll(frame.subList(0, frame.size - 1))
        return all.toIntArray()
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
            "RC5", "RC5X" -> {
                val a = address and 0x1F
                val c = command and 0x3F
                rc5Toggle = rc5Toggle xor 1
                val bits = ArrayList<Int>()
                bits.add(1)
                // RC5X: the second start bit is the inverted 7th command bit
                bits.add(if (protocol == "RC5X" && (command and 0x40) != 0) 0 else 1)
                bits.add(rc5Toggle)
                for (i in 4 downTo 0) bits.add((a shr i) and 1)
                for (i in 5 downTo 0) bits.add((c shr i) and 1)
                Signal(36000, manchester(bits, 888))
            }
            "RC6" -> rc6(address and 0xFF, command and 0xFF)
            "NEC42" -> {
                val a = address and 0x1FFF
                val c = command and 0xFF
                val d1 = a.toLong() or ((a.inv() and 0x1FFF).toLong() shl 13) or ((c and 0x3F).toLong() shl 26)
                val d2 = ((c and 0xC0) shr 6).toLong() or ((c.inv() and 0xFF).toLong() shl 2)
                Signal(38000, pulseDistance(9000, 4500, 560, 1690, 560, 560, d1 or (d2 shl 32), 42, 560).toIntArray())
            }
            "NEC42ext" -> {
                val d1 = (address and 0x3FFFFFF).toLong() or ((command and 0x3F).toLong() shl 26)
                val d2 = ((command and 0xFFC0) shr 6).toLong()
                Signal(38000, pulseDistance(9000, 4500, 560, 1690, 560, 560, d1 or (d2 shl 32), 42, 560).toIntArray())
            }
            "RCA" -> {
                val a = address and 0xF
                val c = command and 0xFF
                val data = a.toLong() or (c.toLong() shl 4) or ((a.inv() and 0xF).toLong() shl 12) or ((c.inv() and 0xFF).toLong() shl 16)
                Signal(38000, pulseDistance(4000, 4000, 500, 2000, 500, 1000, data, 24, 500).toIntArray())
            }
            "Kaseikyo" -> {
                val id = (address ushr 24) and 3
                val vendor = (address ushr 8) and 0xFFFF
                val genre1 = (address ushr 4) and 0xF
                val genre2 = address and 0xF
                val d0 = vendor and 0xFF
                val d1 = vendor ushr 8
                var vp = d0 xor d1
                vp = (vp and 0xF) xor (vp ushr 4)
                val d2 = (vp and 0xF) or (genre1 shl 4)
                val d3 = (genre2 and 0xF) or ((command and 0xF) shl 4)
                val d4 = (id shl 6) or ((command and 0x3FF) ushr 4)
                val d5 = (d2 xor d3 xor d4) and 0xFF
                val data = d0.toLong() or (d1.toLong() shl 8) or (d2.toLong() shl 16) or (d3.toLong() shl 24) or
                    ((d4 and 0xFF).toLong() shl 32) or (d5.toLong() shl 40)
                Signal(38000, pulseDistance(3456, 1728, 432, 1296, 432, 432, data, 48, 432).toIntArray())
            }
            "Pioneer" -> {
                val a = address and 0xFF
                val c = command and 0xFF
                val data = a.toLong() or ((a.inv() and 0xFF).toLong() shl 8) or (c.toLong() shl 16) or ((c.inv() and 0xFF).toLong() shl 24)
                // 33rd data bit is 0; the frame is sent twice with a 26 ms gap
                val frame = pulseDistance(8500, 4225, 500, 1500, 500, 500, data, 33, 0)
                Signal(40000, twice(frame, 26000))
            }
            "SIRC" -> sirc(address, command, 12, 0x1F)
            "SIRC15" -> sirc(address, command, 15, 0xFF)
            "SIRC20" -> sirc(address, command, 20, 0x1FFF)
            else -> null
        }
    }
}
