#!/usr/bin/env node
/*
 * OpenRouter + Groq + Google AI Studio Chat CLI (Node.js)
 * -------------------------------------------------------
 * Terminal-chat met modellen van OpenRouter, Groq en Google AI Studio.
 * Geen npm packages nodig. Vereist Node.js 18 of nieuwer (ingebouwde fetch).
 *
 * Groq-modellen voeg je toe met "groq:" ervoor, bv. groq:llama-3.3-70b-versatile.
 * Google AI Studio-modellen met "gemini:" ervoor, bv. gemini:gemini-2.5-flash.
 * Alle andere model-id's gaan naar OpenRouter.
 *
 * Start:  node openrouter_chat.js
 */

const fs = require("fs");
const os = require("os");
const path = require("path");
const readline = require("readline");

// Providers. OpenRouter (zonder prefix) is de standaard.
const PROVIDERS = {
  openrouter: { prefix: "", url: "https://openrouter.ai/api/v1/chat/completions",
                field: "api_key", cmd: "/key", name: "OpenRouter API key", hint: "sk-or-..." },
  groq:       { prefix: "groq:", url: "https://api.groq.com/openai/v1/chat/completions",
                field: "groq_key", cmd: "/groqkey", name: "Groq API key", hint: "gsk_..." },
  gemini:     { prefix: "gemini:", url: "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                field: "gemini_key", cmd: "/geminikey", name: "Google AI Studio API key", hint: "AQ.... of AIza..." },
};
const CONFIG_PATH = process.env.OPENROUTER_CHAT_CONFIG || path.join(os.homedir(), ".openrouter_chat.json");

const C = { reset: "\x1b[0m", dim: "\x1b[2m", bold: "\x1b[1m", cyan: "\x1b[36m",
            green: "\x1b[32m", yellow: "\x1b[33m", red: "\x1b[31m" };

const HELP = `
${C.bold}Commando's:${C.reset}
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
`;

// ---------- config ----------
function loadConfig() {
  const cfg = { api_key: "", groq_key: "", gemini_key: "", models: [], current: null, system: "" };
  try {
    const j = JSON.parse(fs.readFileSync(CONFIG_PATH, "utf8"));
    for (const p of Object.values(PROVIDERS)) if (typeof j[p.field] === "string") cfg[p.field] = j[p.field];
    if (Array.isArray(j.models)) cfg.models = j.models.filter((m) => typeof m === "string");
    if (typeof j.current === "string") cfg.current = j.current;
    if (typeof j.system === "string") cfg.system = j.system;
  } catch {}
  return cfg;
}

function saveConfig(cfg) {
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(cfg, null, 2), "utf8");
}

// ---------- input ----------
const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
const lines = rl[Symbol.asyncIterator]();
let inputClosed = false;
async function ask(promptText) {
  process.stdout.write(promptText);
  const { value, done } = await lines.next();
  if (done) { inputClosed = true; return null; }
  return value;
}

// ---------- helpers ----------
// Vraagt een API key. Lege invoer = overslaan.
async function askKey(cfg, provider = "openrouter") {
  const p = PROVIDERS[provider];
  const k = ((await ask(`${C.yellow}Plak je ${p.name} (${p.hint}), of Enter om over te slaan: ${C.reset}`)) || "").trim();
  if (k) {
    cfg[p.field] = k;
    saveConfig(cfg);
    console.log(`${C.green}${p.name} opgeslagen.${C.reset}`);
  }
}

// Geeft url, key, model-id en key-commando voor het actieve model.
function route(cfg) {
  const m = cfg.current;
  for (const p of Object.values(PROVIDERS)) {
    if (p.prefix && m.startsWith(p.prefix)) return { url: p.url, key: cfg[p.field], model: m.slice(p.prefix.length), cmd: p.cmd };
  }
  const p = PROVIDERS.openrouter;
  return { url: p.url, key: cfg[p.field], model: m, cmd: p.cmd };
}

// ---------- modellen zoeken ----------
async function errorText(resp) {
  try {
    let j = await resp.json();
    if (Array.isArray(j)) j = j[0];
    return `HTTP ${resp.status}: ${j?.error?.message || resp.statusText}`;
  } catch { return `HTTP ${resp.status}: ${resp.statusText}`; }
}

const kContext = (n) => (n ? `${Math.round(n / 1000)}k context` : "");

// Haalt de beschikbare modellen op. Geeft [{ id, info }] terug (id inclusief prefix).
async function fetchModels(cfg, provider) {
  if (provider === "openrouter") {
    const r = await fetch("https://openrouter.ai/api/v1/models");
    if (!r.ok) throw new Error(await errorText(r));
    const isFree = (m) => m.id.endsWith(":free") ||
      (m.pricing && Number(m.pricing.prompt) === 0 && Number(m.pricing.completion) === 0);
    const isChat = (m) => !(m.architecture?.output_modalities || []).includes("audio"); // geen muziek/spraakmodellen
    return (await r.json()).data.filter((m) => isFree(m) && isChat(m))
      .map((m) => ({ id: m.id, info: kContext(m.context_length) }));
  }
  if (provider === "groq") {
    const r = await fetch("https://api.groq.com/openai/v1/models", {
      headers: { Authorization: `Bearer ${cfg.groq_key}` } });
    if (!r.ok) throw new Error(await errorText(r));
    return (await r.json()).data
      .filter((m) => m.active !== false && !/whisper|tts|playai|orpheus|guard/i.test(m.id)) // alleen chatmodellen
      .map((m) => ({ id: "groq:" + m.id, info: kContext(m.context_window) }));
  }
  // Google AI Studio
  const r = await fetch("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", {
    headers: { "x-goog-api-key": cfg.gemini_key } });
  if (!r.ok) throw new Error(await errorText(r));
  return ((await r.json()).models || [])
    .filter((m) => (m.supportedGenerationMethods || []).includes("generateContent") &&
                   !/embedding|aqa|tts|image/i.test(m.name))
    .map((m) => ({ id: "gemini:" + m.name.replace(/^models\//, ""), info: kContext(m.inputTokenLimit) }));
}

// "1,3,5" / "2-4" / "alle" -> lijst met indexen
function parseSelection(text, n) {
  const out = new Set();
  for (const t of (text || "").toLowerCase().split(/[,\s]+/).filter(Boolean)) {
    if (t === "a" || t === "alle" || t === "all") { for (let i = 0; i < n; i++) out.add(i); continue; }
    const range = t.match(/^(\d+)-(\d+)$/);
    if (range) { for (let i = +range[1]; i <= +range[2]; i++) if (i >= 1 && i <= n) out.add(i - 1); continue; }
    if (/^\d+$/.test(t) && +t >= 1 && +t <= n) out.add(+t - 1);
  }
  return [...out].sort((a, b) => a - b);
}

async function browseModels(cfg) {
  console.log(`\n${C.bold}Waar wil je modellen zoeken?${C.reset}
  1. OpenRouter ${C.dim}(alleen gratis modellen)${C.reset}
  2. Groq ${C.dim}(gratis met limieten)${C.reset}
  3. Google AI Studio ${C.dim}(meeste modellen gratis met limieten)${C.reset}`);
  const choice = ((await ask("Kies 1-3 (Enter = annuleren): ")) || "").trim();
  const provider = { 1: "openrouter", 2: "groq", 3: "gemini" }[choice];
  if (!provider) return;

  const p = PROVIDERS[provider];
  if (provider !== "openrouter" && !cfg[p.field]) {
    console.log(`${C.yellow}Hiervoor heb je eerst een ${p.name} nodig.${C.reset}`);
    await askKey(cfg, provider);
    if (!cfg[p.field]) return;
  }

  console.log(`${C.dim}Modellen ophalen...${C.reset}`);
  let list;
  try { list = await fetchModels(cfg, provider); }
  catch (e) {
    console.log(`${C.red}Ophalen mislukt: ${e.cause?.message || e.message}${C.reset}`);
    if (/40[013]|api key/i.test(e.message) && provider !== "openrouter")
      console.log(`${C.yellow}Klopt je key? Gebruik ${p.cmd} om hem opnieuw in te stellen.${C.reset}`);
    return;
  }
  if (!list.length) return console.log(`${C.dim}Geen modellen gevonden.${C.reset}`);
  list.sort((a, b) => a.id.localeCompare(b.id));

  list.forEach((m, i) => {
    const have = cfg.models.includes(m.id) ? ` ${C.green}(al toegevoegd)${C.reset}` : "";
    console.log(`  ${String(i + 1).padStart(2)}. ${m.id} ${C.dim}${m.info}${C.reset}${have}`);
  });
  const sel = await ask(`Welke wil je toevoegen? (bv. 1,3 of 2-5 of "alle"; Enter = geen): `);
  const idx = parseSelection(sel, list.length);
  if (!idx.length) return console.log(`${C.dim}Niets toegevoegd.${C.reset}`);
  addModels(cfg, idx.map((i) => list[i].id).join(" "));
}

function addModels(cfg, text) {
  const added = [];
  for (const m of (text || "").split(/[,\s]+/).filter(Boolean)) {
    if (!cfg.models.includes(m)) { cfg.models.push(m); added.push(m); }
  }
  if (!cfg.current && cfg.models.length) cfg.current = cfg.models[0];
  saveConfig(cfg);
  console.log(added.length ? `${C.green}Toegevoegd: ${added.join(", ")}${C.reset}`
                           : `${C.dim}Niets nieuws toegevoegd.${C.reset}`);
}

function resolveModel(cfg, arg) {
  arg = arg.trim();
  if (/^\d+$/.test(arg)) return cfg.models[Number(arg) - 1] || null;
  return cfg.models.includes(arg) ? arg : null;
}

function showModels(cfg) {
  if (!cfg.models.length) return console.log(`${C.dim}Nog geen modellen. Gebruik /add <model-id>.${C.reset}`);
  cfg.models.forEach((m, i) => {
    const mark = m === cfg.current ? `${C.green}*${C.reset}` : " ";
    console.log(` ${mark} ${i + 1}. ${m}`);
  });
}

// ---------- API ----------
async function streamChat(cfg, messages) {
  const r = route(cfg);
  let resp;
  try {
    resp = await fetch(r.url, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${r.key}`,
        "Content-Type": "application/json",
        "X-Title": "OpenRouter Chat CLI",
      },
      body: JSON.stringify({ model: r.model, messages, stream: true }),
    });
  } catch (e) {
    console.log(`${C.red}Verbindingsfout: ${e.cause?.message || e.message}${C.reset}`);
    return null;
  }

  if (!resp.ok) {
    let msg = resp.statusText;
    try {
      let j = await resp.json();
      if (Array.isArray(j)) j = j[0]; // Google geeft fouten soms als lijst
      msg = j?.error?.message || msg;
    } catch {}
    console.log(`${C.red}HTTP ${resp.status}: ${msg}${C.reset}`);
    if (resp.status === 401 || resp.status === 403 || /api key/i.test(msg)) console.log(`${C.yellow}Je API key klopt niet. Gebruik ${r.cmd} om hem opnieuw in te stellen.${C.reset}`);
    return null;
  }

  const decoder = new TextDecoder();
  let buf = "";
  let full = "";
  for await (const chunk of resp.body) {
    buf += decoder.decode(chunk, { stream: true });
    let nl;
    while ((nl = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (!line.startsWith("data:")) continue; // keep-alives
      const data = line.slice(5).trim();
      if (data === "[DONE]") { process.stdout.write("\n"); return full; }
      let obj;
      try { obj = JSON.parse(data); } catch { continue; }
      if (obj.error) {
        console.log(`\n${C.red}Fout: ${obj.error.message || JSON.stringify(obj.error)}${C.reset}`);
        return null;
      }
      const piece = obj.choices?.[0]?.delta?.content;
      if (piece) { full += piece; process.stdout.write(piece); }
    }
  }
  process.stdout.write("\n");
  return full;
}

// ---------- main loop ----------
async function main() {
  const major = Number(process.versions.node.split(".")[0]);
  if (major < 18) {
    console.log(`${C.red}Node.js 18 of nieuwer nodig (jij hebt ${process.version}).${C.reset}`);
    process.exit(1);
  }

  const cfg = loadConfig();
  console.log(`${C.bold}${C.cyan}=== OpenRouter + Groq + Google AI Studio Chat CLI ===${C.reset}  (typ /help voor commando's)\n`);

  if (!Object.values(PROVIDERS).some((p) => cfg[p.field])) {
    for (const name of Object.keys(PROVIDERS)) await askKey(cfg, name);
  }
  if (!cfg.models.length) {
    console.log(`\n${C.yellow}Je hebt nog geen modellen. Laten we er een paar zoeken.${C.reset}`);
    await browseModels(cfg);
  }

  const history = [];

  while (!inputClosed) {
    const model = cfg.current || "geen model";
    let user = await ask(`\n${C.bold}${C.green}jij${C.reset} ${C.dim}[${model}]${C.reset} > `);
    if (user === null) { console.log("\nDoei!"); break; }
    user = user.trim();
    if (!user) continue;

    // --- commando's ---
    if (user.startsWith("/")) {
      const sp = user.indexOf(" ");
      const cmd = (sp < 0 ? user : user.slice(0, sp)).toLowerCase();
      let arg = sp < 0 ? "" : user.slice(sp + 1);

      if (["/quit", "/exit", "/q"].includes(cmd)) { console.log("Doei!"); break; }
      else if (cmd === "/help") console.log(HELP);
      else if (cmd === "/models") showModels(cfg);
      else if (cmd === "/zoek" || cmd === "/browse") await browseModels(cfg);
      else if (cmd === "/add") {
        if (!arg.trim()) arg = await ask("Model-id('s): ");
        addModels(cfg, arg);
      } else if (cmd === "/use") {
        const m = resolveModel(cfg, arg);
        if (m) {
          cfg.current = m; saveConfig(cfg);
          console.log(`${C.green}Nu actief: ${m}${C.reset} ${C.dim}(gesprek blijft behouden)${C.reset}`);
        } else console.log(`${C.red}Onbekend model. Zie /models.${C.reset}`);
      } else if (cmd === "/remove") {
        const m = resolveModel(cfg, arg);
        if (m) {
          cfg.models = cfg.models.filter((x) => x !== m);
          if (cfg.current === m) cfg.current = cfg.models[0] || null;
          saveConfig(cfg);
          console.log(`Verwijderd: ${m}`);
        } else console.log(`${C.red}Onbekend model. Zie /models.${C.reset}`);
      } else if (cmd === "/system") {
        cfg.system = arg.trim(); saveConfig(cfg);
        console.log("System prompt " + (cfg.system ? "ingesteld." : "verwijderd."));
      } else if (cmd === "/clear") {
        history.length = 0;
        console.log(`${C.dim}Gesprek gewist.${C.reset}`);
      } else if (cmd === "/key") await askKey(cfg);
      else if (cmd === "/groqkey") await askKey(cfg, "groq");
      else if (cmd === "/geminikey") await askKey(cfg, "gemini");
      else console.log(`${C.red}Onbekend commando. Typ /help.${C.reset}`);
      continue;
    }

    // --- chatten ---
    if (!cfg.current) { console.log(`${C.red}Geen model. Gebruik /add <model-id>.${C.reset}`); continue; }
    const rt = route(cfg);
    if (!rt.key) { console.log(`${C.red}Geen API key voor dit model. Gebruik ${rt.cmd}.${C.reset}`); continue; }

    history.push({ role: "user", content: user });
    const messages = (cfg.system ? [{ role: "system", content: cfg.system }] : []).concat(history);

    process.stdout.write(`${C.bold}${C.cyan}ai${C.reset} > `);
    const reply = await streamChat(cfg, messages);
    if (reply) history.push({ role: "assistant", content: reply });
    else history.pop(); // mislukte vraag niet bewaren
  }
  rl.close();
}

main();
