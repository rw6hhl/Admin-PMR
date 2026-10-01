$f = "D:\PMRapk\app\src\main\java\com\pmr\admin\AudioEngine.java"
$c = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8)
if ($c -notmatch 'isDuplex') {
    if ($c.TrimEnd().EndsWith("}")) {
        $c = $c.TrimEnd()
        $c = $c.Substring(0, $c.Length - 1)
    }
    $c = $c + "`r`n    public boolean isDuplex() { return true; }`r`n}"
    [System.IO.File]::WriteAllText($f, $c, (New-Object System.Text.UTF8Encoding $false))
    Write-Host "OK: isDuplex inserted"
} else {
    Write-Host "OK: isDuplex already present"
}