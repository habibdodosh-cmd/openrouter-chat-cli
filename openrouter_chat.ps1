<#
  OpenRouter + Groq + Google AI Studio Chat CLI (PowerShell)
  ----------------------------------------------------------
  Terminal-chat met modellen van OpenRouter, Groq en Google AI Studio. Geen installatie nodig.

  Groq-modellen voeg je toe met "groq:" ervoor, bv. groq:llama-3.3-70b-versatile.
  Google AI Studio-modellen met "gemini:" ervoor, bv. gemini:gemini-2.5-flash.
  Alle andere model-id's gaan naar OpenRouter.

  Start:  powershell -ExecutionPolicy Bypass -File .\openrouter_chat.ps1
#>

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# Providers: prefix, url, config-veld, key-commando, naam, hint
$Providers = [ordered]@{
    openrouter = @{ prefix = '';        url = 'https://openrouter.ai/api/v1/chat/completions';
                    field = 'api_key';    cmd = '/key';       name = 'OpenRouter API key';       hint = 'sk-or-...' }
    groq       = @{ prefix = 'groq:';   url = 'https://api.groq.com/openai/v1/chat/completions';
                    field = 'groq_key';   cmd = '/groqkey';   name = 'Groq API key';             hint = 'gsk_...' }
    gemini     = @{ prefix = 'gemini:'; url = 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions';
                    field = 'gemini_key'; cmd = '/geminikey'; name = 'Google AI Studio API key'; hint = 'AQ.... of AIza...' }
}
$ConfigPath = if ($env:OPENROUTER_CHAT_CONFIG) { $env:OPENROUTER_CHAT_CONFIG } else { Join-Path $HOME '.openrouter_chat.json' }

$Help = @"

Commando's:
  /models            toon je opgeslagen modellen
  /zoek              modellen zoeken en kiezen uit een lijst (OpenRouter gratis, Groq, Google)
  /add <model-id>    model handmatig toevoegen (meerdere tegelijk mag, gescheiden door spatie/komma)
                     "groq:" = Groq, "gemini:" = Google AI Studio, anders OpenRouter
  /use <nr|id>       wissel van model (nummer uit /models of het id)
  /remove <nr|id>    model verwijderen
  /system <tekst>    system prompt instellen (leeg = verwijderen)
  /clear             gesprek wissen (nieuw gesprek)
  /key               OpenRouter API key instellen
  /groqkey           Groq API key instellen
  /geminikey         Google AI Studio API key instellen
  /help              deze hulp
  /quit              afsluiten

"@

# ---------- config ----------
function Load-Config {
    $cfg = @{ api_key = ''; groq_key = ''; gemini_key = ''; models = @(); current = $null; system = '' }
    if (Test-Path $ConfigPath) {
        try {
            $j = Get-Content $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($j.api_key) { $cfg.api_key = [string]$j.api_key }
            if ($j.groq_key) { $cfg.groq_key = [string]$j.groq_key }
            if ($j.gemini_key) { $cfg.gemini_key = [string]$j.gemini_key }
            if ($j.models)  { $cfg.models  = @($j.models | ForEach-Object { [string]$_ }) }
            if ($j.current) { $cfg.current = [string]$j.current }
            if ($j.system)  { $cfg.system  = [string]$j.system }
        } catch { }
    }
    return $cfg
}

function Save-Config($cfg) {
    $obj = [ordered]@{
        api_key  = $cfg.api_key
        groq_key = $cfg.groq_key
        gemini_key = $cfg.gemini_key
        models  = @($cfg.models)
        current = $cfg.current
        system  = $cfg.system
    }
    $json = ConvertTo-Json $obj -Depth 5
    [IO.File]::WriteAllText($ConfigPath, $json, (New-Object System.Text.UTF8Encoding $false))
}

function Ask-Key($cfg, [string]$Provider = 'openrouter') {
    # Lege invoer = overslaan
    $p = $Providers[$Provider]
    $key = Read-Host "Plak je $($p.name) ($($p.hint)), of Enter om over te slaan"
    if ($key) { $key = $key.Trim() }
    if ($key) {
        $cfg[$p.field] = $key
        Save-Config $cfg
        Write-Host "$($p.name) opgeslagen." -ForegroundColor Green
    }
}

function Get-Route($cfg) {
    # Geeft url, key, model-id en het key-commando voor het actieve model
    $m = [string]$cfg.current
    foreach ($p in $Providers.Values) {
        if ($p.prefix -and $m.StartsWith($p.prefix)) {
            return @{ url = $p.url; key = $cfg[$p.field]; model = $m.Substring($p.prefix.Length); cmd = $p.cmd }
        }
    }
    $p = $Providers['openrouter']
    return @{ url = $p.url; key = $cfg[$p.field]; model = $m; cmd = $p.cmd }
}

function Add-Models($cfg, [string]$text) {
    $added = @()
    foreach ($m in ($text -split '[,\s]+' | Where-Object { $_ })) {
        if ($cfg.models -notcontains $m) {
            $cfg.models = @($cfg.models) + $m
            $added += $m
        }
    }
    if (-not $cfg.current -and $cfg.models.Count -gt 0) { $cfg.current = $cfg.models[0] }
    Save-Config $cfg
    if ($added.Count) { Write-Host ("Toegevoegd: " + ($added -join ', ')) -ForegroundColor Green }
    else { Write-Host 'Niets nieuws toegevoegd.' -ForegroundColor DarkGray }
}

function Resolve-Model($cfg, [string]$arg) {
    $arg = $arg.Trim()
    if ($arg -match '^\d+$') {
        $i = [int]$arg - 1
        if ($i -ge 0 -and $i -lt $cfg.models.Count) { return $cfg.models[$i] }
        return $null
    }
    if ($cfg.models -contains $arg) { return $arg }
    return $null
}

function Show-Models($cfg) {
    if ($cfg.models.Count -eq 0) {
        Write-Host 'Nog geen modellen. Gebruik /add <model-id>.' -ForegroundColor DarkGray
        return
    }
    for ($i = 0; $i -lt $cfg.models.Count; $i++) {
        $m = $cfg.models[$i]
        if ($m -eq $cfg.current) { Write-Host ' * ' -ForegroundColor Green -NoNewline } else { Write-Host '   ' -NoNewline }
        Write-Host ("{0}. {1}" -f ($i + 1), $m)
    }
}

# ---------- API ----------
$Http = New-Object System.Net.Http.HttpClient
$Http.Timeout = [TimeSpan]::FromMinutes(5)

# ---------- modellen zoeken ----------
function Get-Json([string]$url, [hashtable]$headers = @{}) {
    # GET-verzoek; geeft het geparste antwoord terug of gooit een fout met een leesbare melding
    $req = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::Get), $url
    foreach ($k in $headers.Keys) { $req.Headers.Add($k, $headers[$k]) }
    try {
        $resp = $Http.SendAsync($req).GetAwaiter().GetResult()
    } catch {
        throw "Verbindingsfout: $($_.Exception.InnerException.Message)"
    }
    $txt = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $resp.IsSuccessStatusCode) {
        $msg = $resp.ReasonPhrase
        try { $e = @($txt | ConvertFrom-Json)[0].error; if ($e.message) { $msg = $e.message } } catch { }
        throw "HTTP $([int]$resp.StatusCode): $msg"
    }
    return ($txt | ConvertFrom-Json)
}

function Format-Context($n) { if ($n) { '{0}k context' -f [math]::Round([double]$n / 1000) } else { '' } }

function Get-ProviderModels($cfg, [string]$provider) {
    # Geeft een lijst van @{ id; info } terug (id inclusief prefix)
    switch ($provider) {
        'openrouter' {
            $data = (Get-Json 'https://openrouter.ai/api/v1/models').data
            return @($data | Where-Object {
                    ($_.id.EndsWith(':free') -or ($_.pricing -and [double]$_.pricing.prompt -eq 0 -and [double]$_.pricing.completion -eq 0)) -and
                    (@($_.architecture.output_modalities) -notcontains 'audio')   # geen muziek/spraakmodellen
                } | ForEach-Object { @{ id = $_.id; info = (Format-Context $_.context_length) } })
        }
        'groq' {
            $data = (Get-Json 'https://api.groq.com/openai/v1/models' @{ Authorization = "Bearer $($cfg.groq_key)" }).data
            return @($data | Where-Object { $_.active -ne $false -and $_.id -notmatch 'whisper|tts|playai|orpheus|guard' } |
                ForEach-Object { @{ id = "groq:$($_.id)"; info = (Format-Context $_.context_window) } })
        }
        'gemini' {
            $data = (Get-Json 'https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000' @{ 'x-goog-api-key' = $cfg.gemini_key }).models
            return @($data | Where-Object { @($_.supportedGenerationMethods) -contains 'generateContent' -and $_.name -notmatch 'embedding|aqa|tts|image' } |
                ForEach-Object { @{ id = 'gemini:' + ($_.name -replace '^models/', ''); info = (Format-Context $_.inputTokenLimit) } })
        }
    }
}

function ConvertTo-Selection([string]$text, [int]$n) {
    # "1,3,5" / "2-4" / "alle" -> gesorteerde lijst met indexen
    $out = New-Object 'System.Collections.Generic.SortedSet[int]'
    foreach ($t in ("$text".ToLower() -split '[,\s]+' | Where-Object { $_ })) {
        if ($t -in 'a', 'alle', 'all') { 0..($n - 1) | ForEach-Object { [void]$out.Add($_) }; continue }
        if ($t -match '^(\d+)-(\d+)$') {
            foreach ($i in [int]$Matches[1]..[int]$Matches[2]) { if ($i -ge 1 -and $i -le $n) { [void]$out.Add($i - 1) } }
            continue
        }
        if ($t -match '^\d+$' -and [int]$t -ge 1 -and [int]$t -le $n) { [void]$out.Add([int]$t - 1) }
    }
    return @($out)
}

function Find-Models($cfg) {
    Write-Host ""
    Write-Host 'Waar wil je modellen zoeken?'
    Write-Host '  1. OpenRouter ' -NoNewline; Write-Host '(alleen gratis modellen)' -ForegroundColor DarkGray
    Write-Host '  2. Groq ' -NoNewline; Write-Host '(gratis met limieten)' -ForegroundColor DarkGray
    Write-Host '  3. Google AI Studio ' -NoNewline; Write-Host '(meeste modellen gratis met limieten)' -ForegroundColor DarkGray
    $choice = "$(Read-Host 'Kies 1-3 (Enter = annuleren)')".Trim()
    $provider = @{ '1' = 'openrouter'; '2' = 'groq'; '3' = 'gemini' }[$choice]
    if (-not $provider) { return }

    $p = $Providers[$provider]
    if ($provider -ne 'openrouter' -and -not $cfg[$p.field]) {
        Write-Host "Hiervoor heb je eerst een $($p.name) nodig." -ForegroundColor Yellow
        Ask-Key $cfg $provider
        if (-not $cfg[$p.field]) { return }
    }

    Write-Host 'Modellen ophalen...' -ForegroundColor DarkGray
    try { $list = @(Get-ProviderModels $cfg $provider) }
    catch {
        $err = "$($_.Exception.Message)"
        Write-Host "Ophalen mislukt: $err" -ForegroundColor Red
        if ($provider -ne 'openrouter' -and $err -match '40[013]|api key') {
            Write-Host "Klopt je key? Gebruik $($p.cmd) om hem opnieuw in te stellen." -ForegroundColor Yellow
        }
        return
    }
    if ($list.Count -eq 0) { Write-Host 'Geen modellen gevonden.' -ForegroundColor DarkGray; return }
    $list = @($list | Sort-Object { $_.id })

    for ($i = 0; $i -lt $list.Count; $i++) {
        Write-Host ('  {0,2}. {1} ' -f ($i + 1), $list[$i].id) -NoNewline
        Write-Host $list[$i].info -ForegroundColor DarkGray -NoNewline
        if ($cfg.models -contains $list[$i].id) { Write-Host ' (al toegevoegd)' -ForegroundColor Green } else { Write-Host '' }
    }
    $sel = Read-Host 'Welke wil je toevoegen? (bv. 1,3 of 2-5 of "alle"; Enter = geen)'
    $idx = @(ConvertTo-Selection $sel $list.Count)
    if ($idx.Count -eq 0) { Write-Host 'Niets toegevoegd.' -ForegroundColor DarkGray; return }
    Add-Models $cfg (($idx | ForEach-Object { $list[$_].id }) -join ' ')
}

function Invoke-ChatStream($cfg, $messages) {
    $route = Get-Route $cfg
    $body = ConvertTo-Json @{ model = $route.model; messages = @($messages); stream = $true } -Depth 10 -Compress

    $req = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::Post), $route.url
    $req.Headers.Add('Authorization', "Bearer $($route.key)")
    $req.Headers.Add('X-Title', 'OpenRouter Chat CLI')
    $req.Content = New-Object System.Net.Http.StringContent $body, ([System.Text.Encoding]::UTF8), 'application/json'

    try {
        $resp = $Http.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    } catch {
        Write-Host "Verbindingsfout: $($_.Exception.InnerException.Message)" -ForegroundColor Red
        return $null
    }

    if (-not $resp.IsSuccessStatusCode) {
        $txt = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $msg = $resp.ReasonPhrase
        try { $e = ($txt | ConvertFrom-Json).error; if ($e.message) { $msg = $e.message } } catch { }
        Write-Host "HTTP $([int]$resp.StatusCode): $msg" -ForegroundColor Red
        if ([int]$resp.StatusCode -in 401, 403 -or "$msg" -match 'api key') {
            Write-Host "Je API key klopt niet. Gebruik $($route.cmd) om hem opnieuw in te stellen." -ForegroundColor Yellow
        }
        return $null
    }

    $stream = $resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $reader = New-Object System.IO.StreamReader $stream, ([System.Text.Encoding]::UTF8)
    $sb = New-Object System.Text.StringBuilder
    try {
        while (-not $reader.EndOfStream) {
            $line = $reader.ReadLine()
            if (-not $line -or -not $line.StartsWith('data:')) { continue }   # keep-alives
            $data = $line.Substring(5).Trim()
            if ($data -eq '[DONE]') { break }
            try { $chunk = $data | ConvertFrom-Json } catch { continue }
            if ($chunk.error) {
                Write-Host ""
                Write-Host "Fout: $($chunk.error.message)" -ForegroundColor Red
                return $null
            }
            if (-not $chunk.choices) { continue }
            $piece = $chunk.choices[0].delta.content
            if ($piece) {
                [void]$sb.Append($piece)
                [Console]::Write($piece)
            }
        }
    } finally {
        $reader.Dispose(); $resp.Dispose()
    }
    Write-Host ""
    return $sb.ToString()
}

# ---------- main loop ----------
$cfg = Load-Config
Write-Host '=== OpenRouter + Groq + Google AI Studio Chat CLI ===' -ForegroundColor Cyan -NoNewline
Write-Host "  (typ /help voor commando's)`n"

if (-not ($Providers.Values | Where-Object { $cfg[$_.field] })) {
    foreach ($name in @($Providers.Keys)) { Ask-Key $cfg $name }
}
if ($cfg.models.Count -eq 0) {
    Write-Host ""
    Write-Host 'Je hebt nog geen modellen. Laten we er een paar zoeken.' -ForegroundColor Yellow
    Find-Models $cfg
}

$history = New-Object System.Collections.ArrayList

while ($true) {
    $model = if ($cfg.current) { $cfg.current } else { 'geen model' }
    Write-Host ""
    Write-Host 'jij ' -ForegroundColor Green -NoNewline
    Write-Host "[$model]" -ForegroundColor DarkGray -NoNewline
    $user = Read-Host ' '
    if ($null -eq $user) { break }          # einde invoer
    $user = $user.Trim()
    if (-not $user) { continue }

    # --- commando's ---
    if ($user.StartsWith('/')) {
        $parts = $user.Split(' ', 2)
        $cmd = $parts[0].ToLower()
        $arg = if ($parts.Count -gt 1) { $parts[1] } else { '' }

        switch ($cmd) {
            { $_ -in '/quit', '/exit', '/q' } { Write-Host 'Doei!'; $Http.Dispose(); return }
            '/help'   { Write-Host $Help }
            '/models' { Show-Models $cfg }
            { $_ -in '/zoek', '/browse' } { Find-Models $cfg }
            '/add' {
                if (-not $arg.Trim()) { $arg = Read-Host "Model-id('s)" }
                Add-Models $cfg $arg
            }
            '/use' {
                $m = Resolve-Model $cfg $arg
                if ($m) {
                    $cfg.current = $m; Save-Config $cfg
                    Write-Host "Nu actief: $m (gesprek blijft behouden)" -ForegroundColor Green
                } else { Write-Host 'Onbekend model. Zie /models.' -ForegroundColor Red }
            }
            '/remove' {
                $m = Resolve-Model $cfg $arg
                if ($m) {
                    $cfg.models = @($cfg.models | Where-Object { $_ -ne $m })
                    if ($cfg.current -eq $m) { $cfg.current = if ($cfg.models.Count) { $cfg.models[0] } else { $null } }
                    Save-Config $cfg
                    Write-Host "Verwijderd: $m"
                } else { Write-Host 'Onbekend model. Zie /models.' -ForegroundColor Red }
            }
            '/system' {
                $cfg.system = $arg.Trim(); Save-Config $cfg
                Write-Host ('System prompt ' + $(if ($cfg.system) { 'ingesteld.' } else { 'verwijderd.' }))
            }
            '/clear' { $history.Clear(); Write-Host 'Gesprek gewist.' -ForegroundColor DarkGray }
            '/key'     { Ask-Key $cfg }
            '/groqkey'   { Ask-Key $cfg 'groq' }
            '/geminikey' { Ask-Key $cfg 'gemini' }
            default  { Write-Host 'Onbekend commando. Typ /help.' -ForegroundColor Red }
        }
        continue
    }

    # --- chatten ---
    if (-not $cfg.current) { Write-Host 'Geen model. Gebruik /add <model-id>.' -ForegroundColor Red; continue }
    $r = Get-Route $cfg
    if (-not $r.key) { Write-Host "Geen API key voor dit model. Gebruik $($r.cmd)." -ForegroundColor Red; continue }

    [void]$history.Add(@{ role = 'user'; content = $user })
    $messages = @()
    if ($cfg.system) { $messages += @{ role = 'system'; content = $cfg.system } }
    $messages += $history.ToArray()

    Write-Host 'ai > ' -ForegroundColor Cyan -NoNewline
    $reply = Invoke-ChatStream $cfg $messages
    if ($reply) {
        [void]$history.Add(@{ role = 'assistant'; content = $reply })
    } else {
        $history.RemoveAt($history.Count - 1)   # mislukte vraag niet bewaren
    }
}
$Http.Dispose()
