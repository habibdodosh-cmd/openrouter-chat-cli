# OpenRouter Chat CLI

Chatten in de terminal met AI-modellen van **OpenRouter**, **Groq** en **Google AI Studio**.
Hetzelfde programma in vier talen: **PowerShell**, **Python**, **Node.js** en **Java**.
Je hoeft geen extra packages te installeren.

## Direct starten vanaf GitHub

Je hoeft niets te downloaden. Kies één van deze commando's:

**PowerShell** (Windows PowerShell 5.1 of PowerShell 7)
```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/habibdodosh-cmd/openrouter-chat-cli/main/openrouter_chat.ps1)))
```

**Python** (3.8 of nieuwer; gebruik `python3` of `python3.14` als `python` niet bestaat)
```bash
python -c "import urllib.request as u;exec(u.urlopen('https://raw.githubusercontent.com/habibdodosh-cmd/openrouter-chat-cli/main/openrouter_chat.py').read())"
```

**Node.js** (18 of nieuwer)
```bash
node -e "fetch('https://raw.githubusercontent.com/habibdodosh-cmd/openrouter-chat-cli/main/openrouter_chat.js').then(r=>r.text()).then(eval)"
```

**Java** (11 of nieuwer). Java kan geen code direct vanaf een URL draaien, dus dit commando zet het bestand eerst in je TEMP-map (PowerShell):
```powershell
irm https://raw.githubusercontent.com/habibdodosh-cmd/openrouter-chat-cli/main/OpenRouterChat.java -OutFile $env:TEMP\OpenRouterChat.java; java $env:TEMP\OpenRouterChat.java
```

## Lokaal starten

```bash
powershell -ExecutionPolicy Bypass -File .\openrouter_chat.ps1
python openrouter_chat.py
node openrouter_chat.js
java OpenRouterChat.java
```

## Zo werkt het

1. Bij de eerste start vraagt hij je API keys. Met Enter sla je er een over.
   - OpenRouter: [openrouter.ai/keys](https://openrouter.ai/keys)
   - Groq: [console.groq.com/keys](https://console.groq.com/keys)
   - Google AI Studio: [aistudio.google.com/api-keys](https://aistudio.google.com/api-keys)
2. Daarna zoek je met `/zoek` modellen uit een lijst. OpenRouter toont alleen de gratis modellen.
3. Typ je bericht. Het antwoord verschijnt live terwijl het model schrijft.

Modellen van Groq beginnen met `groq:` en die van Google met `gemini:`. Alle andere modellen gaan naar OpenRouter.

## Commando's

| Commando | Wat het doet |
|---|---|
| `/zoek` | modellen zoeken en kiezen uit een lijst |
| `/models` | je opgeslagen modellen tonen |
| `/add <model-id>` | een model handmatig toevoegen |
| `/use <nr\|id>` | van model wisselen (het gesprek blijft bewaard) |
| `/remove <nr\|id>` | een model verwijderen |
| `/system <tekst>` | een system prompt instellen (leeg = verwijderen) |
| `/clear` | een nieuw gesprek beginnen |
| `/key`, `/groqkey`, `/geminikey` | een API key instellen |
| `/help` | hulp tonen |
| `/quit` | afsluiten |

## Waar je keys staan

Je keys en modellen worden alleen lokaal opgeslagen, in `~/.openrouter_chat.json` in je gebruikersmap.
Alle vier de versies gebruiken dat bestand. Er staan **geen** keys in deze repository, en ze worden ook nooit naar GitHub gestuurd.
