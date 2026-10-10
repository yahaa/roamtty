package io.itermux.app.session

/** Windows PowerShell 5.1 collector. CIM queries are local; missing optional counters remain unknown. */
internal object WindowsHostMetrics {
    val script = """
        ${'$'}ErrorActionPreference='Stop'
        [Console]::OutputEncoding=New-Object Text.UTF8Encoding
        [Threading.Thread]::CurrentThread.CurrentCulture=[Globalization.CultureInfo]::InvariantCulture
        function put(${'$'}k,${'$'}v) { if (${'$'}null -ne ${'$'}v) { [Console]::WriteLine("${'$'}k="+([string]${'$'}v -replace '[\r\n]',' ')) } }
        try { ${'$'}os=Get-CimInstance Win32_OperatingSystem -OperationTimeoutSec 5 } catch { exit 3 }
        put 'platform' 'windows'
        put 'hostname' ${'$'}os.CSName
        put 'os' ${'$'}os.Caption
        put 'kernel' ("Windows "+${'$'}os.Version)
        put 'arch' ${'$'}os.OSArchitecture
        put 'kb_MemTotal' ${'$'}os.TotalVisibleMemorySize
        put 'kb_MemAvailable' ${'$'}os.FreePhysicalMemory
        put 'kb_SwapTotal' ${'$'}os.SizeStoredInPagingFiles
        put 'kb_SwapFree' ${'$'}os.FreeSpaceInPagingFiles
        put 'users' ${'$'}os.NumberOfUsers
        if (${'$'}os.LastBootUpTime) { put 'uptime' ([long]((Get-Date)-${'$'}os.LastBootUpTime).TotalSeconds) }
        ${'$'}cores=0
        try {
            ${'$'}cpu=@(Get-CimInstance Win32_Processor -OperationTimeoutSec 2)
            ${'$'}cores=(${'$'}cpu | Measure-Object NumberOfLogicalProcessors -Sum).Sum
            put 'cpus' ${'$'}cores
            put 'model' ${'$'}cpu[0].Name
            ${'$'}busy=0.0; ${'$'}count=0
            foreach (${'$'}p in ${'$'}cpu) {
                if (${'$'}null -ne ${'$'}p.LoadPercentage -and ${'$'}p.NumberOfLogicalProcessors -gt 0) {
                    ${'$'}busy+=${'$'}p.LoadPercentage*${'$'}p.NumberOfLogicalProcessors; ${'$'}count+=${'$'}p.NumberOfLogicalProcessors
                }
            }
            if (${'$'}count -gt 0) { put 'cpu' (${'$'}busy/${'$'}count) }
        } catch {}
        try {
            Get-CimInstance Win32_LogicalDisk -OperationTimeoutSec 2 -Filter 'DriveType=3' | Sort-Object @{Expression={${'$'}_.DeviceID -ne ${'$'}os.SystemDrive}},DeviceID | ForEach-Object {
                if (${'$'}_.Size -gt 0 -and ${'$'}null -ne ${'$'}_.FreeSpace) {
                    put 'disk' ('{0:F0} {1:F0} {2:F0} {3}/' -f (${'$'}_.Size/1KB),((${'$'}_.Size-${'$'}_.FreeSpace)/1KB),(${'$'}_.FreeSpace/1KB),${'$'}_.DeviceID)
                }
            }
        } catch {}
        try {
            ${'$'}rx=0L; ${'$'}tx=0L
            foreach (${'$'}nic in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
                if (${'$'}nic.NetworkInterfaceType -eq 'Loopback') { continue }
                ${'$'}s=${'$'}nic.GetIPStatistics(); ${'$'}rx+=${'$'}s.BytesReceived; ${'$'}tx+=${'$'}s.BytesSent
            }
            put 'net' "${'$'}rx ${'$'}tx"
        } catch {}
        try {
            if (${'$'}cores -gt 0 -and ${'$'}os.TotalVisibleMemorySize -gt 0) {
                Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -OperationTimeoutSec 2 | Where-Object { ${'$'}_.Name -ne '_Total' -and ${'$'}_.Name -ne 'Idle' -and ${'$'}null -ne ${'$'}_.PercentProcessorTime -and ${'$'}null -ne ${'$'}_.WorkingSet } | Sort-Object PercentProcessorTime -Descending | Select-Object -First 5 | ForEach-Object {
                    put 'proc' ('{0:F2} {1:F2} {2}' -f ${'$'}_.PercentProcessorTime,(100*${'$'}_.WorkingSet/(${'$'}os.TotalVisibleMemorySize*1KB)),${'$'}_.Name)
                }
            }
        } catch {}
        exit 0
    """.trimIndent().lineSequence().joinToString("\n") { it.trim() }

    /** Separate optional query so driver errors/timeouts cannot discard a valid system sample. */
    val gpuScript = """
        [Console]::OutputEncoding=New-Object Text.UTF8Encoding
        ${'$'}tool=Get-Command nvidia-smi.exe -CommandType Application -ErrorAction SilentlyContinue
        if (-not ${'$'}tool) { exit 0 }
        function query(${'$'}fields,${'$'}key) {
            ${'$'}p=New-Object Diagnostics.Process
            try {
                ${'$'}p.StartInfo.FileName=${'$'}tool.Source
                ${'$'}p.StartInfo.Arguments="--query-${'$'}fields --format=csv,noheader,nounits"
                ${'$'}p.StartInfo.UseShellExecute=${'$'}false
                ${'$'}p.StartInfo.RedirectStandardOutput=${'$'}true
                ${'$'}p.StartInfo.RedirectStandardError=${'$'}true
                [void]${'$'}p.Start()
                ${'$'}output=${'$'}p.StandardOutput.ReadToEndAsync()
                ${'$'}errors=${'$'}p.StandardError.ReadToEndAsync()
                if (-not ${'$'}p.WaitForExit(1200)) { ${'$'}p.Kill(); return }
                if (${'$'}p.ExitCode -eq 0) { foreach (${'$'}row in ${'$'}output.Result -split '\r?\n') {
                    if (${'$'}row) { [Console]::WriteLine("${'$'}key=${'$'}row") }
                } }
            } catch {} finally { ${'$'}p.Dispose() }
        }
        query 'gpu=index,name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw,power.limit' 'gpu'
        query 'compute-apps=pid,process_name,used_memory' 'gpuproc'
        exit 0
    """.trimIndent()
}
