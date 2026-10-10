# Only the Windows CIM boundary is replaced; collection/formatting/parsing runs unchanged in real PowerShell.
function Get-CimInstance {
    [CmdletBinding()]
    param([string]$ClassName, [string]$Filter, [uint32]$OperationTimeoutSec)
    if ($env:ROAMTTY_METRICS_FAILURE -eq '1') { throw 'CIM access denied' }
    if ($env:ROAMTTY_METRICS_SLOW_PROCESS -eq '1' -and $ClassName -eq 'Win32_PerfFormattedData_PerfProc_Process') {
        if ($OperationTimeoutSec -eq 0) { $OperationTimeoutSec=20 }
        Start-Sleep -Seconds $OperationTimeoutSec
        throw 'CIM operation timed out'
    }
    switch ($ClassName) {
        'Win32_OperatingSystem' {
            [pscustomobject]@{
                CSName='开发机'; Caption='Microsoft Windows 11 专业版'; Version='10.0.26100'; OSArchitecture='64-bit'
                TotalVisibleMemorySize=16777216L; FreePhysicalMemory=12582912L
                SizeStoredInPagingFiles=2097152L; FreeSpaceInPagingFiles=1572864L
                NumberOfUsers=2; LastBootUpTime=(Get-Date).AddSeconds(-3600); SystemDrive='C:'
            }
        }
        'Win32_Processor' {
            if ($env:ROAMTTY_METRICS_OPTIONAL_FAILURE -eq '1') { throw 'CPU unavailable' }
            [pscustomobject]@{ Name='Test CPU'; NumberOfLogicalProcessors=2; LoadPercentage=20 }
            [pscustomobject]@{ Name='Test CPU'; NumberOfLogicalProcessors=6; LoadPercentage=60 }
        }
        'Win32_LogicalDisk' {
            if ($env:ROAMTTY_METRICS_OPTIONAL_FAILURE -eq '1') { throw 'Disk unavailable' }
            if ($Filter -ne 'DriveType=3') { throw 'Collector must request fixed disks' }
            [pscustomobject]@{ DeviceID='D:'; Size=1099511627776L; FreeSpace=824633720832L }
            [pscustomobject]@{ DeviceID='C:'; Size=107374182400L; FreeSpace=64424509440L }
            [pscustomobject]@{ DeviceID='E:'; Size=$null; FreeSpace=$null }
        }
        'Win32_PerfFormattedData_PerfProc_Process' {
            if ($env:ROAMTTY_METRICS_OPTIONAL_FAILURE -eq '1') { throw 'Process unavailable' }
            [pscustomobject]@{ Name='_Total'; PercentProcessorTime=800; WorkingSet=17179869184L }
            [pscustomobject]@{ Name='Idle'; PercentProcessorTime=700; WorkingSet=0 }
            [pscustomobject]@{ Name='compiler'; PercentProcessorTime=200; WorkingSet=1073741824L }
            [pscustomobject]@{ Name='服务 工作'; PercentProcessorTime=50.5; WorkingSet=536870912L }
        }
        default { throw "Unexpected CIM class: $ClassName" }
    }
}
