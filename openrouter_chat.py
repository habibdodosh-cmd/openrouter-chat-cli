#!/usr/bin/env python3
"""
OpenRouter + Groq + Google AI Studio Chat CLI
---------------------------------------------
Simpele terminal-chat met modellen van OpenRouter, Groq en Google AI Studio.
Geen extra packages nodig (alleen de Python standaardbibliotheek).

Groq-modellen voeg je toe met "groq:" ervoor, bv. groq:llama-3.3-70b-versatile.
Google AI Studio-modellen met "gemini:" ervoor, bv. gemini:gemini-2.5-flash.
Alle andere model-id's gaan naar OpenRouter.

Start:  python openrouter_chat.py
"""

import json
import os
import sys
import urllib.request
import urllib.error
from pathlib import Path

# Providers: naam -> (prefix, url, config-veld, key-commando, naam van de key, hint)
PROVIDERS = {
    "openrouter": ("", "https://openrouter.ai/api/v1/chat/completions",
                   "api_key", "/key", "OpenRouter API key", "sk-or-..."),
    "groq": ("groq:", "https://api.groq.com/openai/v1/chat/completions",
             "groq_key", "/groqkey", "Groq API key", "gsk_..."),
    "gemini": ("gemini:", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
               "gemini_key", "/geminikey", "Google AI Studio API key", "AQ.... of AIza..."),
}
# Sommige API's (o.a. Groq) blokkeren de standaard "Python-urllib" user-agent
USER_AGENT = "OpenRouterChatCLI/1.0"
CONFIG_PATH = Path(os.environ.get("OPENROUTER_CHAT_CONFIG") or (Path.home() / ".openrouter_chat.json"))

# Kleuren (werken in Windows Terminal / moderne consoles)
if os.name == "nt":
    os.system("")  # zet ANSI-kleuren aan op Windows
C_RESET = "\033[0m"
C_DIM = "\033[2m"
C_BOLD = "\033[1m"
C_CYAN = "\033[36m"
C_GREEN = "\033[32m"
C_YELLOW = "\033[33m"
C_RED = "\033[31m"

HELP = f"""
{C_BOLD}Commando's:{C_RESET}
  /models            toon je opgeslagen modellen
  /zoek              modellen zoeken en kiezen uit een lijst (OpenRouter gratis, Groq, Google)
  /add <model-id>    model handmatig toevoegen (bv. openai/gpt-4o, groq:llama-3.3-70b-versatile,
                     gemini:gemini-2.5-flash); meerdere tegelijk mag, gescheiden door spatie/komma
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
"""


# ---------- config ----------
def load_config():
    cfg = {"api_key": "", "groq_key": "", "gemini_key": "", "models": [], "current": None, "system": ""}
    if CONFIG_PATH.exists():
        try:
            cfg.update(json.loads(CONFIG_PATH.read_text(encoding="utf-8")))
        except Exception:
            pass
    return cfg


def save_config(cfg):
    CONFIG_PATH.write_text(json.dumps(cfg, indent=2), encoding="utf-8")


def ask_key(cfg, provider="openrouter"):
    """Vraagt een API key. Lege invoer = overslaan."""
    _, _, field, _, name, hint = PROVIDERS[provider]
    key = input(f"{C_YELLOW}Plak je {name} ({hint}), of Enter om over te slaan: {C_RESET}").strip()
    if key:
        cfg[field] = key
        save_config(cfg)
        print(f"{C_GREEN}{name} opgeslagen.{C_RESET}")


def route(cfg):
    """Geeft (url, key, model-id, naam van key-commando) voor het actieve model."""
    m = cfg["current"]
    for prefix, url, field, cmd, _, _ in PROVIDERS.values():
        if prefix and m.startswith(prefix):
            return url, cfg.get(field, ""), m[len(prefix):], cmd
    _, url, field, cmd, _, _ = PROVIDERS["openrouter"]
    return url, cfg.get(field, ""), m, cmd


# ---------- modellen zoeken ----------
def get_json(url, headers=None):
    """GET-verzoek; geeft het geparste antwoord terug of gooit RuntimeError met een leesbare melding."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        msg = e.reason
        try:
            j = json.loads(e.read().decode("utf-8"))
            if isinstance(j, list):
                j = j[0]
            msg = j.get("error", {}).get("message") or msg
        except Exception:
            pass
        raise RuntimeError(f"HTTP {e.code}: {msg}")
    except urllib.error.URLError as e:
        raise RuntimeError(f"Verbindingsfout: {e.reason}")


def k_context(n):
    return f"{round(n / 1000)}k context" if n else ""


def fetch_models(cfg, provider):
    """Geeft een lijst van (id, info) terug (id inclusief prefix)."""
    if provider == "openrouter":
        def is_free(m):
            p = m.get("pricing") or {}
            try:
                zero = float(p.get("prompt", 1)) == 0 and float(p.get("completion", 1)) == 0
            except ValueError:
                zero = False
            return m["id"].endswith(":free") or zero

        def is_chat(m):  # geen muziek/spraakmodellen
            return "audio" not in ((m.get("architecture") or {}).get("output_modalities") or [])

        data = get_json("https://openrouter.ai/api/v1/models")["data"]
        return [(m["id"], k_context(m.get("context_length"))) for m in data if is_free(m) and is_chat(m)]

    if provider == "groq":
        data = get_json("https://api.groq.com/openai/v1/models",
                        {"Authorization": f"Bearer {cfg.get('groq_key', '')}"})["data"]
        skip = ("whisper", "tts", "playai", "orpheus", "guard")
        return [("groq:" + m["id"], k_context(m.get("context_window"))) for m in data
                if m.get("active", True) and not any(s in m["id"].lower() for s in skip)]

    # Google AI Studio
    data = get_json("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
                    {"x-goog-api-key": cfg.get("gemini_key", "")}).get("models", [])
    skip = ("embedding", "aqa", "tts", "image")
    out = []
    for m in data:
        name = m.get("name", "")
        if "generateContent" not in (m.get("supportedGenerationMethods") or []):
            continue
        if any(s in name.lower() for s in skip):
            continue
        if name.startswith("models/"):
            name = name[len("models/"):]
        out.append(("gemini:" + name, k_context(m.get("inputTokenLimit"))))
    return out


def parse_selection(text, n):
    """'1,3,5' / '2-4' / 'alle' -> gesorteerde lijst met indexen."""
    out = set()
    for t in (text or "").lower().replace(",", " ").split():
        if t in ("a", "alle", "all"):
            out.update(range(n))
        elif "-" in t and all(x.isdigit() for x in t.split("-", 1)):
            a, b = map(int, t.split("-", 1))
            out.update(i - 1 for i in range(a, b + 1) if 1 <= i <= n)
        elif t.isdigit() and 1 <= int(t) <= n:
            out.add(int(t) - 1)
    return sorted(out)


def browse_models(cfg):
    print(f"\n{C_BOLD}Waar wil je modellen zoeken?{C_RESET}")
    print(f"  1. OpenRouter {C_DIM}(alleen gratis modellen){C_RESET}")
    print(f"  2. Groq {C_DIM}(gratis met limieten){C_RESET}")
    print(f"  3. Google AI Studio {C_DIM}(meeste modellen gratis met limieten){C_RESET}")
    provider = {"1": "openrouter", "2": "groq", "3": "gemini"}.get(input("Kies 1-3 (Enter = annuleren): ").strip())
    if not provider:
        return

    _, _, field, cmd, name, _ = PROVIDERS[provider]
    if provider != "openrouter" and not cfg.get(field):
        print(f"{C_YELLOW}Hiervoor heb je eerst een {name} nodig.{C_RESET}")
        ask_key(cfg, provider)
        if not cfg.get(field):
            return

    print(f"{C_DIM}Modellen ophalen...{C_RESET}")
    try:
        models = sorted(fetch_models(cfg, provider))
    except RuntimeError as e:
        print(f"{C_RED}Ophalen mislukt: {e}{C_RESET}")
        if provider != "openrouter" and any(s in str(e).lower() for s in ("400", "401", "403", "api key")):
            print(f"{C_YELLOW}Klopt je key? Gebruik {cmd} om hem opnieuw in te stellen.{C_RESET}")
        return
    if not models:
        print(f"{C_DIM}Geen modellen gevonden.{C_RESET}")
        return

    for i, (mid, info) in enumerate(models, 1):
        have = f" {C_GREEN}(al toegevoegd){C_RESET}" if mid in cfg["models"] else ""
        print(f"  {i:2}. {mid} {C_DIM}{info}{C_RESET}{have}")
    idx = parse_selection(input('Welke wil je toevoegen? (bv. 1,3 of 2-5 of "alle"; Enter = geen): '), len(models))
    if not idx:
        print(f"{C_DIM}Niets toegevoegd.{C_RESET}")
        return
    add_models(cfg, " ".join(models[i][0] for i in idx))


def parse_models(text):
    parts = text.replace(",", " ").replace("\n", " ").split()
    return [p.strip() for p in parts if p.strip()]


def add_models(cfg, text):
    added = []
    for m in parse_models(text):
        if m not in cfg["models"]:
            cfg["models"].append(m)
            added.append(m)
    if not cfg["current"] and cfg["models"]:
        cfg["current"] = cfg["models"][0]
    save_config(cfg)
    if added:
        print(f"{C_GREEN}Toegevoegd: {', '.join(added)}{C_RESET}")
    else:
        print(f"{C_DIM}Niets nieuws toegevoegd.{C_RESET}")


def resolve_model(cfg, arg):
    arg = arg.strip()
    if arg.isdigit():
        i = int(arg) - 1
        if 0 <= i < len(cfg["models"]):
            return cfg["models"][i]
        return None
    return arg if arg in cfg["models"] else None


def show_models(cfg):
    if not cfg["models"]:
        print(f"{C_DIM}Nog geen modellen. Gebruik /add <model-id>.{C_RESET}")
        return
    for i, m in enumerate(cfg["models"], 1):
        mark = f"{C_GREEN}*{C_RESET}" if m == cfg["current"] else " "
        print(f" {mark} {i}. {m}")


# ---------- API ----------
def stream_chat(cfg, messages):
    """Stuurt het gesprek naar OpenRouter of Groq en print het antwoord live. Geeft de volledige tekst terug."""
    url, key, model_id, key_cmd = route(cfg)
    body = json.dumps({
        "model": model_id,
        "messages": messages,
        "stream": True,
    }).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Authorization": f"Bearer {key}",
            "User-Agent": USER_AGENT,
            "Content-Type": "application/json",
            "X-Title": "OpenRouter Chat CLI",
        },
        method="POST",
    )

    full = []
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            for raw in resp:
                line = raw.decode("utf-8", errors="replace").strip()
                if not line.startswith("data:"):
                    continue  # keep-alive comments e.d.
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    chunk = json.loads(data)
                except json.JSONDecodeError:
                    continue
                if "error" in chunk:
                    msg = chunk["error"].get("message", chunk["error"])
                    print(f"\n{C_RED}Fout: {msg}{C_RESET}")
                    return None
                choices = chunk.get("choices") or []
                if not choices:
                    continue
                piece = (choices[0].get("delta") or {}).get("content") or ""
                if piece:
                    full.append(piece)
                    sys.stdout.write(piece)
                    sys.stdout.flush()
    except urllib.error.HTTPError as e:
        try:
            detail = json.loads(e.read().decode("utf-8")).get("error", {}).get("message", "")
        except Exception:
            detail = ""
        print(f"{C_RED}HTTP {e.code}: {detail or e.reason}{C_RESET}")
        if e.code in (401, 403) or "api key" in detail.lower():
            print(f"{C_YELLOW}Je API key klopt niet. Gebruik {key_cmd} om hem opnieuw in te stellen.{C_RESET}")
        return None
    except urllib.error.URLError as e:
        print(f"{C_RED}Verbindingsfout: {e.reason}{C_RESET}")
        return None
    except KeyboardInterrupt:
        print(f"\n{C_DIM}(afgebroken){C_RESET}")

    print()
    return "".join(full)


# ---------- main loop ----------
def main():
    cfg = load_config()
    print(f"{C_BOLD}{C_CYAN}=== OpenRouter + Groq + Google AI Studio Chat CLI ==={C_RESET}  (typ /help voor commando's)\n")

    if not any(cfg.get(p[2]) for p in PROVIDERS.values()):
        for name in PROVIDERS:
            ask_key(cfg, name)
    if not cfg["models"]:
        print(f"\n{C_YELLOW}Je hebt nog geen modellen. Laten we er een paar zoeken.{C_RESET}")
        browse_models(cfg)

    history = []

    while True:
        model = cfg["current"] or "geen model"
        try:
            user = input(f"\n{C_BOLD}{C_GREEN}jij{C_RESET} {C_DIM}[{model}]{C_RESET} > ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nDoei!")
            break
        if not user:
            continue

        # --- commando's ---
        if user.startswith("/"):
            cmd, _, arg = user.partition(" ")
            cmd = cmd.lower()
            if cmd in ("/quit", "/exit", "/q"):
                print("Doei!")
                break
            elif cmd == "/help":
                print(HELP)
            elif cmd == "/models":
                show_models(cfg)
            elif cmd in ("/zoek", "/browse"):
                browse_models(cfg)
            elif cmd == "/add":
                if not arg.strip():
                    arg = input("Model-id('s): ")
                add_models(cfg, arg)
            elif cmd == "/use":
                m = resolve_model(cfg, arg)
                if m:
                    cfg["current"] = m
                    save_config(cfg)
                    print(f"{C_GREEN}Nu actief: {m}{C_RESET} {C_DIM}(gesprek blijft behouden){C_RESET}")
                else:
                    print(f"{C_RED}Onbekend model. Zie /models.{C_RESET}")
            elif cmd == "/remove":
                m = resolve_model(cfg, arg)
                if m:
                    cfg["models"].remove(m)
                    if cfg["current"] == m:
                        cfg["current"] = cfg["models"][0] if cfg["models"] else None
                    save_config(cfg)
                    print(f"Verwijderd: {m}")
                else:
                    print(f"{C_RED}Onbekend model. Zie /models.{C_RESET}")
            elif cmd == "/system":
                cfg["system"] = arg.strip()
                save_config(cfg)
                print("System prompt " + ("ingesteld." if cfg["system"] else "verwijderd."))
            elif cmd == "/clear":
                history = []
                print(f"{C_DIM}Gesprek gewist.{C_RESET}")
            elif cmd == "/key":
                ask_key(cfg)
            elif cmd == "/groqkey":
                ask_key(cfg, "groq")
            elif cmd == "/geminikey":
                ask_key(cfg, "gemini")
            else:
                print(f"{C_RED}Onbekend commando. Typ /help.{C_RESET}")
            continue

        # --- chatten ---
        if not cfg["current"]:
            print(f"{C_RED}Geen model. Gebruik /add <model-id>.{C_RESET}")
            continue
        _, key, _, key_cmd = route(cfg)
        if not key:
            print(f"{C_RED}Geen API key voor dit model. Gebruik {key_cmd}.{C_RESET}")
            continue

        history.append({"role": "user", "content": user})
        messages = ([{"role": "system", "content": cfg["system"]}] if cfg.get("system") else []) + history

        print(f"{C_BOLD}{C_CYAN}ai{C_RESET} > ", end="", flush=True)
        reply = stream_chat(cfg, messages)
        if reply:
            history.append({"role": "assistant", "content": reply})
        else:
            history.pop()  # mislukte vraag niet in de geschiedenis houden


if __name__ == "__main__":
    main()
