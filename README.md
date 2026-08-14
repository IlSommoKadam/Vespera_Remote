# Vespera Wi-Fi Helper

L'app mantiene una richiesta Wi-Fi generica e chiede al daemon root `tools/vespera-netd.sh` di associare la rete Vespera come connessione system-wide. Il daemon instrada `10.0.0.0/24` su `wlan0`; Ethernet resta attiva per Internet, ADB e RustDesk. Non viene usata alcuna VPN.

## Versioning

`versionName` segue le revisioni dell'app; `versionCode` resta monotono in `app/build.gradle`.

| Versione | versionCode | Note |
|----------|-------------|------|
| 0.2.0 | 2 | Label versione in UI; default probe su 8082; scan porte include 21/8082/8083 |
| 0.2.1 | 3 | A connessione: scan porte automatico, preferisce 8082/8083 e verifica TCP |
| 0.2.2 | 4 | Auto-discovery ritardata + attesa DHCP + retry se porte ancora chiuse |
| 0.2.3 | 5 | Bridge VPN 10.0.0.0/24 per rendere il Vespera raggiungibile da Singularity |
| 0.3.0 | 6 | Configurazione multi-strumento: scan/selezione/salvataggio Vespera I, II, Pro |
| 0.3.1 | 7 | Pulisce status «raggiungibile» se connessione persa; default SSID/BSSID proprietario |
| 0.3.8 | 14 | Marcatori ● salvato / ✓ connesso |
| 0.4.0 | 15 | Selettore lingua IT/EN/ES in alto a destra; testi UI localizzati |
| 0.5 | 41 | Connessione Vespera system-wide e route diretta; rimossi VPN e fallback locali non funzionanti |

Il daemon deve essere avviato come root sul Pi dopo il boot; l'app comunica con esso tramite `net.req` nella propria directory esterna.
