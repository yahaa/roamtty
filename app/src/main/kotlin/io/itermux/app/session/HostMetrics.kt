package io.itermux.app.session

/** One snapshot of a host's health, read over SSH by [HostMetrics.script]. Unknown values stay null. */
data class HostMetrics(
    val hostname: String? = null,
    val os: String? = null,
    val kernel: String? = null,
    val arch: String? = null,
    val cpuModel: String? = null,
    val cpus: Int? = null,
    val cpuPercent: Double? = null,
    /** 1, 5 and 15 minute load averages. */
    val load: List<Double> = emptyList(),
    /** "running/total" scheduling entities, Linux only. */
    val tasks: String? = null,
    val uptimeSeconds: Long? = null,
    val memTotalBytes: Long? = null,
    val memAvailableBytes: Long? = null,
    val swapTotalBytes: Long? = null,
    val swapFreeBytes: Long? = null,
    val disks: List<Disk> = emptyList(),
    /** Bytes received and sent on all interfaces but loopback since boot. */
    val netRxBytes: Long? = null,
    val netTxBytes: Long? = null,
    val users: Int? = null,
    val processes: List<Process> = emptyList(),
    /** NVIDIA (nvidia-smi) and AMD (amdgpu sysfs) GPUs; empty when the host has none or no driver tool. */
    val gpus: List<Gpu> = emptyList(),
    /** Processes holding GPU memory, most memory first; NVIDIA only. */
    val gpuProcesses: List<GpuProcess> = emptyList(),
) {
    data class Disk(val mount: String, val totalBytes: Long, val usedBytes: Long, val availableBytes: Long) {
        val percent: Double get() = if (usedBytes + availableBytes > 0) 100.0 * usedBytes / (usedBytes + availableBytes) else 0.0
    }
    data class Process(val name: String, val cpuPercent: Double, val memPercent: Double)
    data class Gpu(val index: Int, val name: String, val utilPercent: Double?, val memUsedBytes: Long?, val memTotalBytes: Long?,
        val temperatureC: Double?, val powerWatts: Double?, val powerLimitWatts: Double?) {
        val memPercent: Double? get() = memUsedBytes?.let { used -> memTotalBytes?.takeIf { it > 0 }?.let { 100.0 * used / it } }
    }
    data class GpuProcess(val pid: Long, val name: String, val memBytes: Long?)

    val memUsedBytes: Long? get() = if (memTotalBytes != null && memAvailableBytes != null) (memTotalBytes - memAvailableBytes).coerceAtLeast(0) else null
    val memPercent: Double? get() = memUsedBytes?.let { used -> memTotalBytes?.takeIf { it > 0 }?.let { 100.0 * used / it } }
    val swapUsedBytes: Long? get() = if (swapTotalBytes != null && swapFreeBytes != null) (swapTotalBytes - swapFreeBytes).coerceAtLeast(0) else null

    companion object {
        /** POSIX sh for Linux (/proc) and macOS (sysctl); each line is key=value, missing tools print nothing. */
        val script = """
            export LC_ALL=C
            s=$(uname -s 2>/dev/null)
            echo "hostname=$(hostname 2>/dev/null || uname -n)"
            echo "kernel=$(uname -sr 2>/dev/null)"
            echo "arch=$(uname -m 2>/dev/null)"
            if [ -r /etc/os-release ]; then echo "os=$(sed -n 's/^PRETTY_NAME=//p' /etc/os-release | tr -d '"')"
            elif [ "${'$'}s" = Darwin ]; then echo "os=macOS $(sw_vers -productVersion 2>/dev/null)"; fi
            if [ -r /proc/stat ]; then
              echo "cpus=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null)"
              echo "model=$(sed -n 's/^model name[[:space:]]*:[[:space:]]*//p' /proc/cpuinfo | head -n 1)"
              echo "cpu1=$(head -n 1 /proc/stat)"
              sleep 0.5 2>/dev/null || sleep 1
              echo "cpu2=$(head -n 1 /proc/stat)"
              echo "load=$(cut -d ' ' -f 1-4 /proc/loadavg)"
              echo "uptime=$(cut -d ' ' -f 1 /proc/uptime)"
              awk '/^(MemTotal|MemAvailable|SwapTotal|SwapFree):/ { sub(":", "", ${'$'}1); print "kb_" ${'$'}1 "=" ${'$'}2 }' /proc/meminfo
              sed 1,2d /proc/net/dev | tr ':' ' ' | awk '${'$'}1 != "lo" { rx += ${'$'}2; tx += ${'$'}10 } END { printf "net=%.0f %.0f\n", rx, tx }'
            elif [ "${'$'}s" = Darwin ]; then
              echo "cpus=$(sysctl -n hw.ncpu 2>/dev/null)"
              echo "model=$(sysctl -n machdep.cpu.brand_string 2>/dev/null)"
              echo "idle=$(top -l 2 -n 0 -s 1 2>/dev/null | sed -n 's/.* \([0-9.]*\)% idle.*/\1/p' | tail -n 1)"
              echo "load=$(sysctl -n vm.loadavg 2>/dev/null | tr -d '{}')"
              b=$(sysctl -n kern.boottime 2>/dev/null | sed 's/.*sec = \([0-9]*\).*/\1/')
              [ -n "${'$'}b" ] && echo "uptime=$(( $(date +%s) - b ))"
              echo "bytes_MemTotal=$(sysctl -n hw.memsize 2>/dev/null)"
              vm_stat 2>/dev/null | awk '/page size of/ { p = ${'$'}8 } /^Pages (free|inactive|speculative)/ { gsub("\\.", "", ${'$'}NF); n += ${'$'}NF } END { if (p) printf "bytes_MemAvailable=%.0f\n", n * p }'
              netstat -ibn 2>/dev/null | awk '${'$'}1 !~ /^lo/ && ${'$'}3 ~ /Link/ { rx += ${'$'}7; tx += ${'$'}10 } END { printf "net=%.0f %.0f\n", rx, tx }'
            fi
            df -Pk / "${'$'}HOME" 2>/dev/null | awk 'NR > 1 && !seen[${'$'}6]++ { m = ${'$'}6; for (i = 7; i <= NF; i++) m = m " " ${'$'}i; print "disk=" ${'$'}2 " " ${'$'}3 " " ${'$'}4 " " m }'
            echo "users=$(who 2>/dev/null | wc -l)"
            { ps -eo pcpu=,pmem=,comm= --sort=-pcpu 2>/dev/null || ps -Aro pcpu=,pmem=,comm= 2>/dev/null; } | head -n 5 | sed 's/^/proc=/'
            if command -v nvidia-smi >/dev/null 2>&1; then
              t=; command -v timeout >/dev/null 2>&1 && t="timeout 5"
              ${'$'}t nvidia-smi --query-gpu=index,name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw,power.limit --format=csv,noheader,nounits 2>/dev/null | sed 's/^/gpu=/'
              ${'$'}t nvidia-smi --query-compute-apps=pid,process_name,used_memory --format=csv,noheader,nounits 2>/dev/null | sed 's/^/gpuproc=/'
            fi
            for c in /sys/class/drm/card*; do
              case ${'$'}{c##*/} in *-*) continue ;; esac
              d=${'$'}c/device
              [ -r "${'$'}d/gpu_busy_percent" ] || continue
              h=$(ls -d "${'$'}d"/hwmon/hwmon* 2>/dev/null | head -n 1)
              echo "amdgpu=$(cat "${'$'}d/gpu_busy_percent" 2>/dev/null || echo -) $(cat "${'$'}d/mem_info_vram_used" 2>/dev/null || echo -) $(cat "${'$'}d/mem_info_vram_total" 2>/dev/null || echo -) $(cat "${'$'}h/temp1_input" 2>/dev/null || echo -) $(cat "${'$'}h/power1_average" 2>/dev/null || echo -) $(cat "${'$'}h/power1_cap" 2>/dev/null || echo -) $(cat "${'$'}d/product_name" 2>/dev/null)"
            done
            exit 0
        """.trimIndent()

        fun parse(lines: List<String>): HostMetrics {
            val values = linkedMapOf<String, MutableList<String>>()
            lines.forEach { line ->
                val at = line.indexOf('=')
                if (at > 0) values.getOrPut(line.substring(0, at).trim()) { mutableListOf() } += line.substring(at + 1).trim()
            }
            fun one(key: String) = values[key]?.firstOrNull()?.takeIf { it.isNotEmpty() }
            fun number(key: String) = one(key)?.toLongOrNull()
            val load = one("load")?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
            val net = one("net")?.split(' ')?.mapNotNull { it.toLongOrNull() }
            val nvidia = values["gpu"].orEmpty().mapNotNull(::nvidiaGpu)
            val firstAmd = (nvidia.maxOfOrNull { it.index } ?: -1) + 1
            return HostMetrics(
                hostname = one("hostname"),
                os = one("os"),
                kernel = one("kernel"),
                arch = one("arch"),
                cpuModel = one("model")?.replace(Regex("\\s+"), " "),
                cpus = number("cpus")?.toInt()?.takeIf { it > 0 },
                cpuPercent = cpuPercent(one("cpu1"), one("cpu2")) ?: one("idle")?.toDoubleOrNull()?.let { (100 - it).coerceIn(0.0, 100.0) },
                load = load.take(3).mapNotNull { it.toDoubleOrNull() },
                tasks = load.getOrNull(3)?.takeIf { '/' in it },
                uptimeSeconds = one("uptime")?.toDoubleOrNull()?.toLong(),
                memTotalBytes = number("kb_MemTotal")?.times(1024) ?: number("bytes_MemTotal"),
                memAvailableBytes = number("kb_MemAvailable")?.times(1024) ?: number("bytes_MemAvailable"),
                swapTotalBytes = number("kb_SwapTotal")?.times(1024),
                swapFreeBytes = number("kb_SwapFree")?.times(1024),
                disks = values["disk"].orEmpty().mapNotNull { disk ->
                    val parts = disk.split(' ', limit = 4)
                    val sizes = parts.take(3).mapNotNull { it.toLongOrNull() }
                    if (parts.size == 4 && sizes.size == 3) Disk(parts[3], sizes[0] * 1024, sizes[1] * 1024, sizes[2] * 1024) else null
                },
                netRxBytes = net?.getOrNull(0),
                netTxBytes = net?.getOrNull(1),
                users = number("users")?.toInt(),
                processes = values["proc"].orEmpty().mapNotNull { row ->
                    val parts = row.trim().split(Regex("\\s+"), limit = 3)
                    val cpu = parts.getOrNull(0)?.toDoubleOrNull()
                    val mem = parts.getOrNull(1)?.toDoubleOrNull()
                    val name = parts.getOrNull(2)?.trimEnd('/')?.substringAfterLast('/')
                    if (cpu != null && mem != null && !name.isNullOrEmpty()) Process(name, cpu, mem) else null
                },
                gpus = nvidia + values["amdgpu"].orEmpty().mapIndexedNotNull { i, row -> amdGpu(firstAmd + i, row) },
                gpuProcesses = values["gpuproc"].orEmpty().mapNotNull { row ->
                    val parts = row.split(',').map { it.trim() }
                    val pid = parts.firstOrNull()?.toLongOrNull()
                    val name = parts.drop(1).dropLast(1).joinToString(", ").trimEnd('/').substringAfterLast('/')
                    if (parts.size >= 3 && pid != null && name.isNotEmpty()) GpuProcess(pid, name, mib(parts.last())) else null
                }.sortedByDescending { it.memBytes ?: -1 },
            )
        }

        /** "[N/A]" and "[Not Supported]" read as null. */
        private fun reading(value: String?) = value?.trim()?.toDoubleOrNull()
        private fun mib(value: String?) = reading(value)?.let { (it * 1024 * 1024).toLong() }

        /** One `nvidia-smi --query-gpu` CSV row: index, name, util %, memory MiB used/total, °C, W drawn/limit. */
        internal fun nvidiaGpu(row: String): Gpu? {
            val parts = row.split(',').map { it.trim() }
            if (parts.size < 8) return null
            val index = parts[0].toIntOrNull() ?: return null
            val tail = parts.takeLast(6)
            val name = parts.subList(1, parts.size - 6).joinToString(", ").ifEmpty { "GPU $index" }
            return Gpu(index, name, reading(tail[0]), mib(tail[1]), mib(tail[2]), reading(tail[3]), reading(tail[4]), reading(tail[5]))
        }

        /** amdgpu sysfs: busy %, VRAM bytes used/total, millidegrees, microwatts drawn/cap, then the product name ("-" for missing). */
        internal fun amdGpu(index: Int, row: String): Gpu? {
            val parts = row.trim().split(Regex("\\s+"), limit = 7)
            if (parts.size < 6) return null
            fun n(i: Int) = parts[i].toDoubleOrNull()
            return Gpu(index, parts.getOrNull(6)?.takeIf { it.isNotBlank() } ?: "AMD GPU", n(0), n(1)?.toLong(), n(2)?.toLong(),
                n(3)?.div(1000), n(4)?.div(1_000_000), n(5)?.takeIf { it > 0 }?.div(1_000_000))
        }

        /** Busy share between two `cpu` lines of /proc/stat; iowait counts as idle. */
        internal fun cpuPercent(first: String?, second: String?): Double? {
            fun fields(line: String?) = line?.split(Regex("\\s+"))?.drop(1)?.take(8)?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size >= 5 }
            val a = fields(first) ?: return null
            val b = fields(second) ?: return null
            val total = b.sum() - a.sum()
            if (total <= 0) return null
            val idle = (b[3] + b[4]) - (a[3] + a[4])
            return (100.0 * (total - idle) / total).coerceIn(0.0, 100.0)
        }
    }
}
