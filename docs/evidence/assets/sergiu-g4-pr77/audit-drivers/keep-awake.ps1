Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
public static class AcceptancePower {
    [DllImport("kernel32.dll")]
    public static extern uint SetThreadExecutionState(uint flags);
}
'@
$powerResult = [AcceptancePower]::SetThreadExecutionState([uint32]2147483649)
if ($powerResult -eq 0) { throw 'Could not establish the temporary system-awake request.' }
Write-Output 'Temporary system-awake request established; display settings are unchanged.'
try {
    $awakeDeadline = [DateTime]::UtcNow.AddMinutes(45)
    while ([DateTime]::UtcNow -lt $awakeDeadline -and -not (Test-Path -LiteralPath "$PSScriptRoot/awake-stop")) {
        Start-Sleep -Seconds 30
    }
} finally {
    [void][AcceptancePower]::SetThreadExecutionState([uint32]2147483648)
    Write-Output 'Temporary system-awake request released.'
}
