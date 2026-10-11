# SAMTEX — Algerian CNIBE compatibility report

_Algerian electronic biometric national identity card (Carte Nationale d'Identité Biométrique Électronique)._

This report separates what is **confirmed**, **inferred** and **unsupported**. It also states, for
each feature, whether it has been **verified on a physical Algerian card** or **only tested with
simulated data**.

> **Bottom line:** none of the identity-reading features below has yet been verified on a physical
> CNIBE by the SAMTEX developers. Everything about BAC/PACE, LDS parsing and passive authentication
> has been validated only against a **simulated** ICAO chip with **synthetic** data. Physical-card
> validation is the next required step (checklist at the end).

## 1. What public sources say

| Statement | Source | Status |
|---|---|---|
| The Ministry of the Interior offers a reading service for the CNIBE chip. It needs a PC, a standard contactless reader and the **"DzaEidCard" / "EidDzPlugin"** smart-card module (IE 11 / Firefox ESR). | [interieur.gov.dz – Lecture automatique de la CNIBE](https://interieur.gov.dz/2020/10/25/lecture-automatique-de-la-carte-didentite-nationale-electronique-et-biometrique/), [macnibe.interieur.gov.dz](https://macnibe.interieur.gov.dz/WFDefaultFr.aspx), [bawabatic.dz](https://bawabatic.dz/?req=informations&op=detail&id=97&lang=fr) | Confirmed (official). Page text was obtained through search-engine extracts, because the sites returned HTTP 503 / incomplete TLS chains from our build environment. |
| The official service shows NIN, photo, Latin and Arabic names, date and place of birth, **address and spouse name**. | Same as above | Confirmed (official) |
| The official reading form asks for **card number, date of birth and expiry date**. | macnibe.interieur.gov.dz (WFReadCard pages, as given in the task brief) | Confirmed (official). These are exactly the three inputs of the ICAO Doc 9303 MRZ-derived access key (BAC / PACE-MRZ). |
| The chip hosts several applications: an **ICAO machine-readable application**, fingerprint match-on-card, e-health, e-address and civil-status applications. | Search extract of the Ministry article | Confirmed (official, summary) |
| The card has two microprocessors and runs the **Sealys ID Motion** OS. | [GAAN article](https://members.gaan.dz/articles-divers/la-cnibe--une-technologie-inexploitee-page-15282) | Third-party |
| The card contains an ICAO 9303 chip supporting Active Authentication; Algerian IDs can be read by NFC on smartphones. | [Assurique](https://assurique.com/produits/lecteur-puce), [uqudo](https://uqudo.com/algeria-kyc-aml-services/) | Vendor claims, not technical specifications |

No public specification of the CNIBE file layout, access-control configuration (BAC vs PACE, CAN
availability), data-group usage or national PKI was found.

## 2. Protocols and identifiers

| Item | Status | Basis |
|---|---|---|
| ISO/IEC 14443 Type B + ISO-DEP | **Observed by the user** with SAMTEX (NfcB + IsoDep reported). Not reproduced by the developers. | User report |
| ISO/IEC 14443 Type A, ATQA 0x0008, SAK 0x20, historical bytes `416C67657269614549447632` ("AlgeriaEIDv2") | **Observed by the user** on an earlier scan (task brief). Used only as a test fixture. | User report |
| ICAO LDS1 application, AID `A0000002471001` | **Inferred → indicated.** The ministry mentions an ICAO application, and the user reports SAMTEX's ICAO indication (a successful SELECT). | Official summary + user report |
| Access control: BAC or PACE with MRZ-derived key | **Inferred.** The official inputs equal the MRZ key components. SAMTEX decides at run time: PACE if EF.CardAccess advertises it, otherwise BAC. | ICAO Doc 9303 Part 11 |
| PACE with CAN | **Unknown.** Supported only if a 6-digit CAN is printed on the card. | — |
| EF.CardAccess content (PACE parameters) | **Unknown until read.** SAMTEX reads and displays it publicly (no authentication). | ICAO Doc 9303 Parts 10/11 |
| EF.DIR application list | **Unknown until read.** SAMTEX reads EF.DIR if present instead of guessing AIDs. | ISO/IEC 7816-4 |
| DG1 (MRZ), DG2 (face), DG11, DG12, EF.COM, EF.SOD | **Standard ICAO; presence on CNIBE not verified.** | ICAO Doc 9303 Part 10 |
| Arabic names | **Unknown.** If present in DG11 (UTF-8 "full name in national characters"), SAMTEX shows them. They may instead live in a national application. | — |
| Address, spouse name, NIN, marital status | **Unsupported.** The official service shows them, but through the proprietary DzaEidCard module and the national applications, which are not publicly documented. SAMTEX does not reverse-engineer them. Address/NIN are shown only if the card happens to store them in documented DG11 elements. | — |
| National PKI (CSCA certificate for passive authentication) | **Not bundled.** Must be imported by the user from an official source (e.g. the ICAO PKD, if Algeria participates, or a national publication). | — |
| Fingerprints (DG3), iris (DG4), match-on-card | **Deliberately not accessed.** Protected by EAC/terminal authorization. | Task policy |

## 3. Why the earlier SAMTEX version stopped at technical metadata

1. **No access control was implemented.** The ICAO adapter only sent `SELECT A0000002471001` and then hard-coded the data groups as "restricted".
2. **No way to enter the card data.** Without card number + date of birth + expiry date (or a CAN), the BAC/PACE keys cannot be derived.
3. **Type B blind spot.** Signature and ATS analysis read only Type A historical bytes. Type B cards expose the ATQB and the higher-layer response instead.
4. **No LDS decoding or passive authentication** existed.

All four points are addressed in this version.

## 4. Feature verification matrix

| Feature | Simulated / synthetic tests | Physical CNIBE |
|---|---|---|
| NFC detection, Type A/B, ISO-DEP, ATQB decoding | ✅ unit tests | ⚠️ user-reported only (earlier version) |
| EF.DIR / EF.CardAccess public reads | ✅ simulated card | ❌ not verified |
| BAC (3DES SM) via JMRTD | ✅ simulated chip, ICAO Appendix D key vector | ❌ not verified |
| PACE-MRZ / PACE-CAN (ECDH-GM, brainpoolP256r1, AES-128) via JMRTD | ✅ simulated chip | ❌ not verified |
| Other PACE parameter sets (IM, CAM, other curves, 3DES) | ⚠️ delegated to JMRTD, not simulated | ❌ not verified |
| No BAC downgrade after a PACE authentication failure | ✅ tested | ❌ not verified |
| EF.COM, DG1 (TD1/TD2/TD3), DG11, DG12 parsing | ✅ synthetic files, ICAO MRZ specimens | ❌ not verified |
| DG2 portrait (JPEG) | ✅ synthetic file | ❌ not verified. JPEG 2000 portraits cannot be displayed (no decoder on Android). |
| Passive authentication (hashes, CMS signature, CSCA chain) | ✅ throw-away test PKI | ❌ not verified (no official CSCA tested) |
| Card removal, timeouts, single session | ✅ simulated | ❌ not verified |
| Active / Chip Authentication (clone detection) | ❌ not implemented | — |

## 5. Physical-card validation checklist

Use your own card, or a card whose holder has given consent.

1. Scan the card on the home screen. Record the technologies, ATQB/ATQA, the EF.DIR and EF.CardAccess results, and whether "eMRTD application present" is shown.
2. Tap **Read identity card** and enter the card number, date of birth and expiry date exactly as printed in the MRZ.
3. Note the outcome category and the protocol (PACE/BAC) in *Technical details*.
4. If you get `AUTHENTICATION_FAILED`, check the document number. The MRZ document number may differ from other numbers printed on the card front.
5. Note which DGs are `READ`, `NOT_PRESENT`, `SKIPPED_BY_POLICY` and whether DG11 contains Arabic names.
6. Do **not** share screenshots with personal data. The technical-details panel contains no personal data and can be shared.
