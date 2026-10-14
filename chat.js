const models = ["gemini-flash-lite-latest", "gemini-flash-latest", "gemini-3.8-flash"];
const env = process.env;
const find = (end) => {
  const k = Object.keys(env).find((k) => k.endsWith(end));
  return k ? env[k] : undefined;
};
const DB_URL = env.UPSTASH_REDIS_REST_URL || find("REST_API_URL");
const DB_TOKEN = env.UPSTASH_REDIS_REST_TOKEN || find("REST_API_TOKEN");

const redis = async (cmd) => {
  const r = await fetch(DB_URL, {
    method: "POST",
    headers: { Authorization: "Bearer " + DB_TOKEN },
    body: JSON.stringify(cmd),
  });
  return (await r.json()).result;
};

const load = async (k) => {
  try {
    return JSON.parse((await redis(["GET", k])) || "[]");
  } catch (e) {
    return [];
  }
};

const gemini = async (m, body) => {
  const r = await fetch(
    "https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": env.GEMINI_API_KEY,
      },
      body: JSON.stringify(body),
    }
  );
  return r.json();
};

const tavily = async (query, n = 3) => {
  const t = await fetch("https://api.tavily.com/search", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + env.TAVILY_API_KEY,
    },
    body: JSON.stringify({ query, search_depth: "basic", max_results: n, include_answer: true }),
  });
  const td = await t.json();
  return {
    answer: td.answer || "",
    facts: ((td.answer || "") + " " + (td.results || []).map((x) => x.content).join(" ")).slice(0, 2000),
    raw: td,
  };
};

const users = (() => {
  try { return JSON.parse(env.BUDDY_USERS || "{}"); } catch (e) { return {}; }
})();
const who = (pin) => {
  if (pin && pin === env.BUDDY_PIN) return "main";
  if (pin && users[pin]) return String(users[pin]).replace(/[^a-z0-9_-]/gi, "").slice(0, 20) || "user";
  return null;
};

module.exports = async (req, res) => {
  const u = who(req.headers["x-pin"]);
  if (!u) return res.status(401).json({ reply: "Wrong code." });
  const K_MEM = u === "main" ? "buddy:memory" : "buddy:memory:" + u;
  const K_NOTES = u === "main" ? "buddy:notes" : "buddy:notes:" + u;
  const { q = "hi", history = [], contacts = [], devices = [], image = "", lang = "en-IN" } = req.body || {};
  const done = (reply) => res.status(200).json({ reply, action: { type: "none" } });

  if (image) {
    const vp =
      "You are Buddy, looking through the user's camera. Answer the user's request in 2 to 3 short simple sentences, in " + (String(lang).startsWith("te") ? "Telugu script" : String(lang).startsWith("hi") ? "Hindi script" : "English") + ".  If it is an electronic component, circuit, tool or machine part, name it, say what it does, and give one useful tip. If you cannot tell, say so honestly. No emojis, no lists. User said: " + q;
    let a = "";
    for (let i = 0; i < 3 && !a; i++) {
      const d = await gemini(models[i], {
        contents: [{ parts: [{ text: vp }, { inline_data: { mime_type: "image/jpeg", data: image } }] }],
      });
      a = (d.candidates?.[0]?.content?.parts || []).map((p) => p.text || "").join("");
    }
    return done(a || "I could not see that clearly. Try again with better light.");
  }

  if (/\bconfirm forget\b/i.test(q)) {
    try { await redis(["DEL", K_MEM, K_NOTES]); } catch (e) {}
    return done("Done. I have forgotten everything.");
  }
  if (/\b(forget everything|erase (all )?(my )?memory|delete (all )?(my )?memory|wipe (my )?memory)\b/i.test(q)) {
    return done("That erases everything I remember and all your notes. Say confirm forget if you are sure.");
  }
  if (/\bforget (the )?(last|latest) (thing|one|memory)\b/i.test(q)) {
    const m = await load(K_MEM);
    m.pop();
    try { await redis(["SET", K_MEM, JSON.stringify(m)]); } catch (e) {}
    return done("Okay, I forgot the last thing.");
  }

  const [mem, notes] = await Promise.all([load(K_MEM), load(K_NOTES)]);
  const now = new Date().toLocaleString("en-IN", {
    timeZone: "Asia/Kolkata",
    dateStyle: "medium",
    timeStyle: "short",
  });

  if (/(good morning|morning briefing|brief me|daily briefing|శుభోదయం|सुप्रभात)/i.test(q)) {
    const cityFact = mem.map((m) => m.f).reverse()
      .find((f) => /\b(live|lives|living|stay|stays|staying)\s+in\s+/i.test(f));
    const cm = cityFact && cityFact.match(/\b(?:live|lives|living|stay|stays|staying)\s+in\s+([A-Za-z ]{2,30})/i);
    const city = cm ? cm[1].trim() : "";
    const [news, weather] = await Promise.all([
      tavily("top news India today").catch(() => ({ facts: "" })),
      city ? tavily("weather today in " + city, 2).catch(() => ({ facts: "" })) : Promise.resolve({ facts: "" }),
    ]);
    const plans = mem.slice(-15).map((m) => "[" + m.t + "] " + m.f).join("\n") || "none";
    const noteList = notes.slice(-10).map((n) => "[" + n.t + "] " + n.n).join("\n") || "none";
    const bp =
      "You are Buddy, a warm friend giving a short morning briefing by voice. Use 4 to 5 short simple sentences, in the language the user spoke. Greet them, say the time and date, mention their plans or notes only if there are any, then the weather if given (if no weather is given, say they can tell you which city they live in to get weather), then the top one or two news items. Do not make up facts. No emojis or lists.\n" +
      "Time now: " + now + "\nPlans and facts:\n" + plans + "\nNotes:\n" + noteList + "\n" +
      "Weather facts: " + (weather.facts || "none") + "\nNews facts: " + (news.facts || "none") + "\nUser said: " + q;
    let a = "";
    for (let i = 0; i < 3 && !a; i++) {
      const d = await gemini(models[i], { contents: [{ parts: [{ text: bp }] }] });
      a = (d.candidates?.[0]?.content?.parts || []).map((p) => p.text || "").join("");
    }
    return done(a || "Good morning! It is " + now + ".");
  }

  const known = mem.map((m) => "[" + m.t + "] " + m.f).join("\n") || "nothing yet";
  const noteList = notes.slice(-30).map((n) => "[" + n.t + "] " + n.n).join("\n") || "none";
  const cnames = (Array.isArray(contacts) ? contacts : []).slice(0, 80).map((c) => String(c).slice(0, 30)).join(", ");
  const dnames = (Array.isArray(devices) ? devices : []).slice(0, 10).map((c) => String(c).slice(0, 30)).join(", ");
  const prompt =
    "You are Buddy, a warm, funny friend chatting by voice. Reply in 1-2 short simple sentences, like a real conversation (up to 4 short sentences when explaining a topic, quizzing, or reading notes). When it fits, end with a short question to keep talking, and use what you remember about the user. Don't use emojis or lists. Always reply in the language the user speaks. If they speak Telugu, reply in Telugu script. If Hindi, reply in Hindi script. If the user asks you to translate something, reply with only the translation, in English, Telugu script or Hindi script as asked.\n" +
    "Study mode: if the user asks to be quizzed, ask ONE question at a time. When they answer, say briefly if it is right or wrong with the correct answer, then ask the next question. Use the recent chat to know your last question. If they ask you to explain something in simple Telugu or Hindi, do that in easy words.\n" +
    "Current time: " + now + "\n" +
    "What you remember about the user (with the time they told you):\n" + known + "\n" +
    "User's saved notes:\n" + noteList + "\n" +
    "Recent chat: " + history.join(" | ") + "\n" +
    "Contact names on the user's phone (voice recognition may misspell names, so for call or whatsapp set target to the closest matching name from this list, otherwise keep what they said): " + (cnames || "unknown") + "\n" +
    "Devices the user connected, like a robot: " + (dnames || "none") + ". To control one, use action device (target = the device name from this list, text = the command in a few simple words) and say what you are sending.\n" +
    "User says: " + q + "\n" +
    "If the user shares a lasting fact, or a plan or activity (like going to watch TV, going out, an exam), put it in save as a short sentence, like 'The user is going to watch TV'. Otherwise save is an empty string.\n" +
    "If the user asks you to save a note, note something down, or remember something for later as a note, put the note in note as a short sentence and reply that it is saved. Otherwise note is an empty string. If they ask what their notes are, read up to 5 from the saved notes.\n" +
    "If the user asks you to do something on the phone, fill action and say what you are doing in reply, like 'Opening YouTube for robotics.' Action types: web (target = the question; use for live facts like news, scores, weather, prices, or anything you are unsure about; reply just says Let me check), google (target = search words; use when the user wants to see Google results on screen), youtube (target = search words), call (target = contact name or number), whatsapp (target = contact name or number, text = the message), open (target = app name), alarm (target = time in 24-hour HH:MM, text = label), timer (target = total seconds, text = label), alarms (shows the alarm list; use when asked to delete or edit alarms), playstore (target = app to search), settings (target = wifi, bluetooth, display, sound, battery, location, apps, date, or empty), play (target = song or video to play), media (target = pause, play, next, previous, volumeup, volumedown, mute, flashlighton or flashlightoff), device (target = device name, text = command). Otherwise action type is none.\n" +
    'Return ONLY JSON: {"reply":"...","save":"...","note":"","action":{"type":"none","target":"","text":""}}';
  let text = "";
  for (let i = 0; i < 6 && !text; i++) {
    const d = await gemini(models[i % models.length], {
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: { responseMimeType: "application/json" },
    });
    text = d.candidates?.[0]?.content?.parts?.[0]?.text || "";
    if (!text) await new Promise((s) => setTimeout(s, 700));
  }
  let out;
  try { out = JSON.parse(text); } catch (e) { out = { reply: text, save: "", note: "" }; }
  if (!out.reply) out = { reply: "I'm busy right now, please ask again.", save: "", note: "" };
  if (out.action && out.action.type === "web" && out.action.target) {
    out.reply = "I could not check that right now.";
    try {
      const td = await tavily(out.action.target);
      let a = "";
      for (let i = 0; i < 2 && !a && td.facts.trim() && !(td.answer && /^[\x00-\x7F]*$/.test(q)); i++) {
        const d = await gemini(models[i], {
          contents: [{ parts: [{ text: "Using only these search results, answer in 1-2 short simple sentences, in the same language as the question. Question: " + q + "\nSearch results: " + td.facts }] }],
        });
        a = (d.candidates?.[0]?.content?.parts || []).map((p) => p.text || "").join("");
      }
      out.reply = a || td.answer || ("Search error: " + JSON.stringify(td.raw).slice(0, 200));
    } catch (e) {}
    out.action = { type: "none" };
  }
  if (out.save) {
    try {
      mem.push({ t: now, f: out.save });
      await redis(["SET", K_MEM, JSON.stringify(mem.slice(-200))]);
    } catch (e) {}
  }
  if (out.note) {
    try {
      notes.push({ t: now, n: out.note });
      await redis(["SET", K_NOTES, JSON.stringify(notes.slice(-100))]);
    } catch (e) {}
  }
  res.status(200).json({ reply: out.reply, action: out.action || { type: "none" } });
};
