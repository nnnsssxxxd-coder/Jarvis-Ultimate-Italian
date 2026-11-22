# J.A.R.V.I.S. 4.0 — progetto Android ricostruito

Questo repository è stato ricostruito attorno ai sorgenti Android presenti nel pacchetto `jarvis-4.0-codice.zip`. Include una struttura Gradle autonoma e una GitHub Action che compila l'APK e lo pubblica come artifact.

## Stato reale

- Dashboard Compose, nucleo animato, grafici/telemetria, pagina App, diagnostica e tastiera: sorgenti inclusi.
- Edge Function Supabase `jarvis-ai`: sorgenti e test inclusi in `supabase/`.
- Build Android: automatizzata con GitHub Actions; **non dichiarata riuscita finché la workflow non termina verde**.
- Autenticazione: usa Supabase Auth anonimo con token e refresh token reali, conservati nelle preferenze private dell’app. In Supabase deve essere abilitato **Allow anonymous sign-ins**. Per utenti con account, in una fase successiva va collegato il login esistente.
- Assistente vocale (voce in/out): tocca il nucleo e parla. Ordine: comando locale (ora, data, batteria, timer, sveglia, apri app) -> agente AI via Supabase/OpenAI con ricerca web. Risponde a voce in italiano, a frasi, mentre il testo arriva. Le azioni sensibili (timer/link da AI) chiedono conferma a voce.
- Ascolto continuo: pulsante nel pannello; risponde solo se la frase inizia con "Jarvis" / "Ehi Jarvis". Funziona **solo con l'app aperta e schermo acceso** (non è una hotword di sistema in background).
- HUD sopra le altre app: pulsante HUD nel pannello (richiede il permesso "Mostra sopra altre app").
- Live wallpaper: non incluso.
- Non verificato: tutto il codice vocale/HUD è stato scritto senza poter compilare né provare su telefono.

## Build APK da GitHub (anche da telefono)

1. Crea un repository GitHub vuoto e carica tutti i file di questa cartella nella radice.
2. Apri **Actions → Build JARVIS Android APK → Run workflow**.
3. Attendi il completamento. Scarica l'artifact `JARVIS-4.0-debug-apk` dalla pagina della run: contiene `app-debug.apk`.

Per configurare gli endpoint Android prima della build, aggiungi nei repository secrets:
- `SUPABASE_URL`
- `SUPABASE_PUBLISHABLE_KEY`

Questi sono valori pubblici di configurazione Supabase, non la chiave OpenAI. Abilita anche l’accesso anonimo in Authentication → Sign In / Providers. La chiave OpenAI deve rimanere solo nel secret Edge Function chiamato `jarvis`.

## Backend

Esegui i test Node 22 con:

```sh
./scripts/test-backend.sh
```

Il deploy della Edge Function e la configurazione Auth restano operazioni separate: non vengono eseguite da questa build Android.
