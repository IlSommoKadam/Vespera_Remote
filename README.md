# Vespera Wi-Fi Helper

L'app richiede la rete locale del telescopio usando `WifiNetworkSpecifier` e non imposta mai la rete come processo-predefinita; Ethernet resta quindi disponibile per Internet e RustDesk. Il dump del dispositivo ha rilevato questa specifica rete come `TYPE_OPEN`/`NONE`, perciò la richiesta usa Open invece di OWE: un vincolo OWE mostrava il dialogo Android “No devices found”. La `Network` ricevuta in `onAvailable` va usata per il traffico Vespera, ad esempio `network.bindSocket(socket)` o `network.openConnection(url)`.

## Versioning

Schema `0.2.x` (versionName) con `versionCode` monotono in `app/build.gradle`.

| Versione | versionCode | Note |
|----------|-------------|------|
| 0.2.0 | 2 | Label versione in UI; default probe su 8082; scan porte include 21/8082/8083 |
| 0.2.1 | 3 | A connessione: scan porte automatico, preferisce 8082/8083 e verifica TCP |
| 0.2.2 | 4 | Auto-discovery ritardata + attesa DHCP + retry se porte ancora chiuse |
| 0.2.3 | 5 | Bridge VPN 10.0.0.0/24 per rendere il Vespera raggiungibile da Singularity |

Prossime patch: `0.2.1`, `0.2.2`, … incrementando anche `versionCode`.

## Struttura prevista per versione successiva

Una futura `BootCompletedReceiver` potrà avviare un foreground service che ricrea la stessa richiesta, dopo aver verificato la policy Android al boot. Un bridge (es. VpnService su `10.0.0.0/24`) potrebbe rendere raggiungibile il Vespera anche a Singularity (`com.vaonis.barnard`), oggi esclusa dalla rete `WifiNetworkSpecifier` (AllowedUids solo helper).
