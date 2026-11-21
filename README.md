# Saját Jegyzet (Android 16, Kotlin + Compose)

Jegyzetek + hangrögzítő, Google nélkül. Minden adat sima fájl:

- `notes/<id>.md` – Markdown jegyzetek (az első sor a cím)
- `recordings/rec_YYYYMMDD_HHMMSS.m4a` – AAC felvételek

Alapból az app belső tárolójában vannak; „Minden fájl elérése" jog megadása után a közös
`/storage/emulated/0/SajatJegyzet` mappába kerülnek (ezt látja a Syncthing).

## Build

1. Android Studio (Ladybug vagy újabb) → **Open** → ez a mappa.
2. Sync után: **Run ▶** a telefonra (USB-hibakeresés), vagy **Build → Build APK(s)**.
3. SDK: Android 16 (API 36) platform kell.

## Funkciók

- Jegyzetek: létrehozás, szerkesztés, keresés, törlés (kilépéskor automatikus mentés)
- Hangrögzítő: előtér-szolgáltatás (képernyő lezárva is rögzít), lejátszás, átnevezés, törlés
- Export: ZIP az összes jegyzettel és felvétellel (Beállítások fül)
- Szinkron Syncthinggel: Beállítások → „Hozzáférés megadása" (Minden fájl elérése). Ezután az adatok a
  `/storage/emulated/0/SajatJegyzet` mappában vannak; ezt add hozzá a Syncthing-Fork appban, és oszd meg a szerveroddal.
  Ajánlott ignore minta a Syncthingben: `*.part`
- Szinkron (opcionális): WebDAV (Nextcloud, ownCloud, Synology…). Az URL a mappára mutasson, pl.
  `https://cloud.pelda.hu/remote.php/dav/files/NEV/SajatJegyzet`. App-jelszót használj.
- Import: .txt/.md fájlok (pl. Xiaomi Jegyzetekből exportálva/megosztva) és Google Keep Takeout .json
- Átirat: a felvételhez az „Átirat" gomb a saját szerveredre (OpenAI-kompatibilis /v1/audio/transcriptions,
  pl. faster-whisper-server) küldi a hangot, az eredmény új jegyzet lesz (így szinkronizálódik is)
- Automatikus háttérszinkron óránként (WorkManager, csak hálózattal) – a WebDAV-hoz

## Szinkron szabályai (WebDAV)

Ami csak az egyik oldalon van, átmásolódik; ha mindkettőn van, az újabb módosítású nyer.
A törlés nem szinkronizálódik (szándékosan, adatvesztés ellen).

## Ismert korlátok (v0.2)

- Még nem fordítottam le (a szerveren nincs Android SDK), első buildnél lehet apró javítás.
- A jelszó sima SharedPreferences-ben van (app-jelszót használj).
- Nincs saját ikon, nincs hullámforma.
- Cleartext HTTP engedélyezve (LAN szerverekhez); lehetőleg HTTPS-t használj.
- Az átirathoz szerver kell; telefonon belüli (offline) átirat későbbi fázis.
